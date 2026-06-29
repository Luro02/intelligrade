/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.state;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import edu.kit.kastel.sdq.artemis4j.grading.Annotation;
import edu.kit.kastel.sdq.artemis4j.grading.Assessment;
import edu.kit.kastel.sdq.artemis4j.grading.ClonedProgrammingSubmission;
import edu.kit.kastel.sdq.artemis4j.grading.CorrectionRound;
import edu.kit.kastel.sdq.artemis4j.grading.location.LineColumn;
import edu.kit.kastel.sdq.artemis4j.grading.location.Location;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.GradingConfig;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.MistakeType;
import edu.kit.kastel.sdq.intelligrade.autograder.AutograderTask;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.AutograderOption;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;

public class ActiveAssessment {
    private static final Logger LOG = Logger.getInstance(ActiveAssessment.class);

    public static final Path ASSIGNMENT_SUB_PATH = Path.of("assignment");

    private final List<Consumer<List<Annotation>>> annotationsUpdatedListener = new ArrayList<>();

    private final Assessment assessment;
    private final ClonedProgrammingSubmission clonedSubmission;
    private final Project project;
    private final ProjectState projectState;

    public ActiveAssessment(Assessment assessment, ClonedProgrammingSubmission clonedSubmission, Project project) {
        this.assessment = assessment;
        this.clonedSubmission = clonedSubmission;
        this.project = project;
        this.projectState = ProjectState.getInstance(project);
    }

    public void registerAnnotationsUpdatedListener(Consumer<List<Annotation>> listener) {
        annotationsUpdatedListener.add(listener);
        notifyAnnotationListener(listener, assessment.getAnnotations(true));
    }

    public GradingConfig getGradingConfig() {
        return assessment.getConfig();
    }

    public boolean isReview() {
        return assessment.getCorrectionRound() == CorrectionRound.REVIEW;
    }

    private static LineColumn translateToLineColumn(Document document, int offset) {
        // The line number in the document is 0-based, and LineColumn expects 0-based as well.
        int line = document.getLineNumber(offset);
        // The column is the offset in the line (0-based), it should satisfy:
        // lineStartOffset + column = offset
        // <-> column = offset - lineStartOffset
        int column = offset - document.getLineStartOffset(line);

        return new LineColumn(line, column);
    }

    private static Location createLocationFromSelection(Editor editor, String path) {
        var caret = editor.getCaretModel().getPrimaryCaret();

        if (!caret.hasSelection()) {
            // highlight the entire line if no selection was made:
            int offset = ReadAction.computeBlocking(caret::getOffset);
            int lineNumber = editor.getDocument().getLineNumber(offset);
            return new Location(path, lineNumber, lineNumber);
        }

        TextRange textRange = ReadAction.computeBlocking(caret::getSelectionRange);

        int startOffset = textRange.getStartOffset();
        int endOffset = textRange.getEndOffset();

        if (startOffset == endOffset) {
            // no selection made -> highlight the entire line
            int lineNumber = editor.getDocument().getLineNumber(startOffset);
            return new Location(path, lineNumber, lineNumber);
        }

        var start = translateToLineColumn(editor.getDocument(), startOffset);
        // The end offset provided by the text range is exclusive (last character is not included),
        // therefore 1 is subtracted to get the correct end position:
        var end = translateToLineColumn(editor.getDocument(), endOffset - 1);

        return new Location(path, start, end);
    }

    private Editor getActiveEditor() {
        return FileEditorManager.getInstance(project).getSelectedTextEditor();
    }

    public void addAnnotationAtCaret(MistakeType mistakeType, boolean withCustomMessage) {
        if (assessment == null) {
            throw new IllegalStateException("No active assessment");
        }

        var editor = getActiveEditor();
        if (editor == null) {
            // no editor open or no selection made
            ArtemisUtils.displayGenericErrorBalloon(
                    "No code selected", "Cannot create annotation without code selection");
            return;
        }

        var target = ReadAction.computeBlocking(() -> createAnnotationTarget(editor));
        if (target.isEmpty()) {
            ArtemisUtils.displayGenericErrorBalloon(
                    "No local file selected", "Cannot create annotation without a local source file");
            return;
        }

        if (mistakeType.isCustomAnnotation()) {
            addCustomAnnotation(mistakeType, target.get().location());
        } else if (withCustomMessage) {
            addPredefinedAnnotationWithCustomMessage(mistakeType, target.get().location());
        } else {
            assessment.addPredefinedAnnotation(mistakeType, target.get().location(), null);
            this.notifyListeners();
        }
    }

    private Optional<AnnotationTarget> createAnnotationTarget(Editor editor) {
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isInLocalFileSystem()) {
            return Optional.empty();
        }

        var path = projectState
                .getProjectRootDirectory()
                .resolve(ASSIGNMENT_SUB_PATH)
                .relativize(file.toNioPath())
                .toString()
                .replace("\\", "/");

        return Optional.of(new AnnotationTarget(createLocationFromSelection(editor, path)));
    }

    public void deleteAnnotation(Annotation annotation) {
        if (this.isReview()) {
            this.assessment.suppressAnnotation(annotation);
        } else {
            this.assessment.removeAnnotation(annotation);
        }
        this.notifyListeners();
    }

    public void restoreAnnotation(Annotation annotation) {
        if (this.isReview()) {
            this.assessment.unsuppressAnnotation(annotation);
        } else {
            ArtemisUtils.displayGenericWarningBalloon(
                    "Cannot restore annotation", "You can only restore annotations in review mode.");
            LOG.warn("Cannot restore annotation outside of review");
        }
        this.notifyListeners();
    }

    public void runAutograder() {
        if (this.isReview()) {
            return;
        }

        var settings = ArtemisSettingsState.getInstance();
        if (settings.getAutograderOption() == AutograderOption.SKIP) {
            return;
        }

        AutograderTask.execute(project, assessment, clonedSubmission, this::notifyListeners);
    }

    public Assessment getAssessment() {
        return this.assessment;
    }

    public void changeCustomMessage(Annotation annotation) {
        if (this.isReview()) {
            // Annotations can't be changed in review mode
            ArtemisUtils.displayInvalidReviewOperationBalloon();
            return;
        }

        if (annotation.getMistakeType().isCustomAnnotation()) {
            showCustomAnnotationDialog(
                    annotation.getMistakeType(),
                    annotation.getCustomMessage().orElseThrow(),
                    annotation.getCustomScore().orElseThrow(),
                    messageWithPoints -> {
                        annotation.setCustomMessage(messageWithPoints.message());
                        annotation.setCustomScore(messageWithPoints.points());
                        this.notifyListeners();
                    });
        } else {
            showCustomMessageDialog(annotation.getCustomMessage().orElse(""), customMessage -> {
                if (customMessage.isBlank()) {
                    annotation.setCustomMessage(null);
                } else {
                    annotation.setCustomMessage(customMessage);
                }
                this.notifyListeners();
            });
        }
    }

    private void addPredefinedAnnotationWithCustomMessage(MistakeType mistakeType, Location location) {
        showCustomMessageDialog("", customMessage -> {
            this.assessment.addPredefinedAnnotation(mistakeType, location, customMessage);
            this.notifyListeners();
        });
    }

    private void addCustomAnnotation(MistakeType mistakeType, Location location) {
        showCustomAnnotationDialog(mistakeType, "", 0.0, messageWithPoints -> {
            this.assessment.addCustomAnnotation(
                    mistakeType, location, messageWithPoints.message(), messageWithPoints.points());
            this.notifyListeners();
        });
    }

    private void notifyListeners() {
        var annotations = this.assessment.getAnnotations(true);
        for (Consumer<List<Annotation>> listener : this.annotationsUpdatedListener) {
            notifyAnnotationListener(listener, annotations);
        }
    }

    private static void notifyAnnotationListener(Consumer<List<Annotation>> listener, List<Annotation> annotations) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            listener.accept(annotations);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> listener.accept(annotations));
    }

    public void showCustomMessageDialog(String initialMessage, Consumer<String> onOk) {
        CustomMessageDialogBuilder.create(initialMessage, project)
                .onSubmit(messageWithPoints -> onOk.accept(messageWithPoints.message()))
                .showNotModal();
    }

    private void showCustomAnnotationDialog(
            MistakeType mistakeType,
            String initialMessage,
            double initialPoints,
            Consumer<CustomMessageDialogBuilder.MessageWithPoints> onOk) {
        CustomMessageDialogBuilder.create(initialMessage, project)
                .onSubmit(onOk)
                .allowCustomScore(mistakeType, initialPoints)
                .showNotModal();
    }

    private record AnnotationTarget(Location location) {}
}
