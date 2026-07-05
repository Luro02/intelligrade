/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.extensions.guis;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javax.swing.JButton;
import javax.swing.event.DocumentEvent;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.TextBrowseFolderListener;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.TextComponentEmptyText;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import com.intellij.util.ui.JBFont;
import edu.kit.kastel.sdq.artemis4j.ArtemisNetworkException;
import edu.kit.kastel.sdq.artemis4j.grading.CorrectionRound;
import edu.kit.kastel.sdq.artemis4j.grading.ProgrammingExercise;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.GradingConfig;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.InvalidGradingConfigException;
import edu.kit.kastel.sdq.intelligrade.AssessmentTracker;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.listeners.AssessmentStateListener;
import edu.kit.kastel.sdq.intelligrade.listeners.ExerciseListener;
import edu.kit.kastel.sdq.intelligrade.state.ActiveAssessment;
import edu.kit.kastel.sdq.intelligrade.state.ProjectState;
import net.miginfocom.swing.MigLayout;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

class GeneralPanel extends JBPanel<GeneralPanel> {
    private static final Logger LOG = Logger.getInstance(GeneralPanel.class);
    private static final int GRADING_CONFIG_VALIDATION_DELAY_MS = 400;

    private final Project project;
    private final Alarm gradingConfigValidationAlarm;
    private final AtomicInteger gradingConfigValidationRequest = new AtomicInteger();
    private final JBLabel modeInfoLabel;
    private final JButton startGradingRound1Button;
    private final JButton startGradingRound2Button;
    private final JButton openInstructorDialogButton;
    private final TextFieldWithBrowseButton gradingConfigPathInput;
    private @Nullable ProgrammingExercise activeExercise;
    private @Nullable ActiveAssessment activeAssessment;
    private @Nullable String gradingConfigValidationError;
    private boolean reviewMode;
    private boolean instructorDialogAllowed;

    GeneralPanel(Disposable parentDisposable, Project project) {
        super(new MigLayout("wrap 1, hidemode 3", "[grow]"));

        this.project = project;
        this.gradingConfigValidationAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, parentDisposable);

        modeInfoLabel = new JBLabel();
        modeInfoLabel.setForeground(JBColor.GREEN);
        modeInfoLabel.setFont(JBFont.h3().asBold());
        add(modeInfoLabel, "align center");

        startGradingRound1Button = ExercisePanel.createWrappingButton("Start Grading Round 1");
        startGradingRound1Button.setForeground(JBColor.GREEN);
        startGradingRound1Button.addActionListener(
                _ -> ProjectState.getInstance(project).startNextAssessment(CorrectionRound.FIRST));
        add(startGradingRound1Button, "grow");

        startGradingRound2Button = ExercisePanel.createWrappingButton("Start Grading Round 2");
        startGradingRound2Button.setForeground(JBColor.GREEN);
        startGradingRound2Button.addActionListener(
                _ -> ProjectState.getInstance(project).startNextAssessment(CorrectionRound.SECOND));
        add(startGradingRound2Button, "grow");

        openInstructorDialogButton = ExercisePanel.createWrappingButton("Show All Submissions");
        openInstructorDialogButton.setForeground(JBColor.GREEN);
        openInstructorDialogButton.addActionListener(_ -> SubmissionsInstructorDialog.showDialog(project));
        openInstructorDialogButton.setVisible(false);
        add(openInstructorDialogButton, "grow");

        gradingConfigPathInput = createGradingConfigPathInput(parentDisposable);
        add(gradingConfigPathInput, "grow");

        ProjectState.getInstance(project).subscribe(parentDisposable, new ExerciseListener() {
            @Override
            public void exerciseChanged(@Nullable ProgrammingExercise exercise) {
                activeExercise = exercise;
                instructorDialogAllowed = false;
                updateState();

                if (exercise == null) {
                    return;
                }

                // Enable/disable instructor button(s)
                ApplicationManager.getApplication()
                        .executeOnPooledThread(() -> updateInstructorDialogPermission(exercise));
            }

            @Override
            public void configChanged(GradingConfig.@Nullable GradingConfigDTO config) {
                reviewMode = config != null && config.review();
                updateState();
            }
        });

        AssessmentTracker.getInstance(project).subscribe(parentDisposable, new AssessmentStateListener() {
            @Override
            public void activeAssessmentChanged(@Nullable ActiveAssessment assessment) {
                activeAssessment = assessment;
                updateState();
            }
        });
    }

    @RequiresBackgroundThread
    private void updateInstructorDialogPermission(ProgrammingExercise exercise) {
        boolean isInstructor;
        try {
            isInstructor =
                    exercise.getCourse().isInstructor(exercise.getConnection().getAssessor());
        } catch (ArtemisNetworkException exception) {
            LOG.warn(exception);
            isInstructor = false;
        }

        boolean instructor = isInstructor;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || activeExercise != exercise) {
                return;
            }

            instructorDialogAllowed = instructor;
            updateState();
        });
    }

    @RequiresEdt
    private void updateState() {
        boolean hasExercise = activeExercise != null;
        boolean isAssessing = activeAssessment != null;

        modeInfoLabel.setText(reviewMode ? "Review Mode (you have a review config)" : "");
        openInstructorDialogButton.setVisible(reviewMode);
        openInstructorDialogButton.setEnabled(reviewMode && instructorDialogAllowed);

        gradingConfigPathInput.setEnabled(!isAssessing);

        startGradingRound1Button.setEnabled(!isAssessing && !reviewMode && hasExercise);
        startGradingRound2Button.setEnabled(
                !isAssessing && !reviewMode && activeExercise != null && activeExercise.hasSecondCorrectionRound());
    }

    private TextFieldWithBrowseButton createGradingConfigPathInput(Disposable parentDisposable) {
        var input = new TextFieldWithBrowseButton();

        input.addBrowseFolderListener(
                new TextBrowseFolderListener(FileChooserDescriptorFactory.createSingleFileDescriptor("json")));
        input.setText(ArtemisSettingsState.getInstance().getSelectedGradingConfigPath());
        gradingConfigValidationError = initialGradingConfigValidationError(input.getText());
        input.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NonNull DocumentEvent documentEvent) {
                scheduleGradingConfigValidation(input.getText());
            }
        });
        new ComponentValidator(parentDisposable)
                .withValidator(() -> validateGradingConfigPath(input))
                .andRegisterOnDocumentListener(input.getTextField())
                .installOn(input.getTextField());

        var innerTextField = (JBTextField) input.getTextField();
        innerTextField.getEmptyText().setText("Path to grading config");
        innerTextField.putClientProperty(TextComponentEmptyText.STATUS_VISIBLE_FUNCTION, (Predicate<JBTextField>)
                field -> field.getText().isEmpty());

        return input;
    }

    private ValidationInfo validateGradingConfigPath(TextFieldWithBrowseButton input) {
        return gradingConfigValidationError == null ? null : new ValidationInfo(gradingConfigValidationError, input);
    }

    private @Nullable String initialGradingConfigValidationError(String gradingConfigPath) {
        if (gradingConfigPath.isBlank()) {
            return "No grading config selected";
        }

        if (!ProjectState.getInstance(project).hasLoadedGradingConfig()) {
            return "Selected grading config is invalid";
        }

        return null;
    }

    @RequiresEdt
    private void scheduleGradingConfigValidation(String rawGradingConfigPath) {
        var gradingConfigPath = normalizeGradingConfigPath(rawGradingConfigPath);
        ProjectState.getInstance(project).setSelectedGradingConfigPath(gradingConfigPath);

        var request = gradingConfigValidationRequest.incrementAndGet();
        gradingConfigValidationAlarm.cancelAllRequests();

        var immediateError = getImmediateGradingConfigValidationError(gradingConfigPath);
        if (immediateError != null) {
            updateGradingConfigValidationError(immediateError);
            return;
        }

        updateGradingConfigValidationError(null);
        gradingConfigValidationAlarm.addRequest(
                () -> validateGradingConfigPathInBackground(request, gradingConfigPath),
                GRADING_CONFIG_VALIDATION_DELAY_MS);
    }

    private @Nullable String normalizeGradingConfigPath(String gradingConfigPath) {
        var trimmedPath = gradingConfigPath.trim();
        return trimmedPath.isEmpty() ? null : trimmedPath;
    }

    private @Nullable String getImmediateGradingConfigValidationError(@Nullable String gradingConfigPath) {
        if (gradingConfigPath == null) {
            return "No grading config selected";
        }

        if (!gradingConfigPath.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return "Grading config must be a JSON file";
        }

        return null;
    }

    @RequiresBackgroundThread
    private void validateGradingConfigPathInBackground(int request, String gradingConfigPath) {
        if (isStaleGradingConfigValidationRequest(request, gradingConfigPath)) {
            return;
        }

        try {
            var path = Path.of(gradingConfigPath);
            var gradingConfigFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (gradingConfigFile == null || gradingConfigFile.isDirectory() || !gradingConfigFile.exists()) {
                applyGradingConfigValidationError(request, gradingConfigPath, "Grading config file not found");
                return;
            }

            var fileContent = VfsUtilCore.loadText(gradingConfigFile);
            var gradingConfigDTO = GradingConfig.readDTOFromString(fileContent);
            applyValidGradingConfig(request, gradingConfigPath, gradingConfigDTO);
        } catch (InvalidPathException invalidPathException) {
            applyGradingConfigValidationError(request, gradingConfigPath, "Invalid grading config path");
        } catch (IOException | InvalidGradingConfigException exception) {
            applyGradingConfigValidationError(request, gradingConfigPath, exception.getMessage());
        }
    }

    private boolean isStaleGradingConfigValidationRequest(int request, String gradingConfigPath) {
        return gradingConfigValidationRequest.get() != request
                || ProjectState.getInstance(project).hasDifferentSelectedGradingConfigPath(gradingConfigPath);
    }

    private void applyValidGradingConfig(
            int request, String gradingConfigPath, GradingConfig.GradingConfigDTO gradingConfigDTO) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || isStaleGradingConfigValidationRequest(request, gradingConfigPath)) {
                return;
            }

            if (ProjectState.getInstance(project).setLoadedGradingConfig(gradingConfigPath, gradingConfigDTO)) {
                updateGradingConfigValidationError(null);
            }
        });
    }

    private void applyGradingConfigValidationError(int request, String gradingConfigPath, @Nullable String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || isStaleGradingConfigValidationRequest(request, gradingConfigPath)) {
                return;
            }

            ProjectState.getInstance(project).clearLoadedGradingConfig(gradingConfigPath);
            updateGradingConfigValidationError(
                    message == null || message.isBlank() ? "Selected grading config is invalid" : message);
        });
    }

    @RequiresEdt
    private void updateGradingConfigValidationError(@Nullable String error) {
        gradingConfigValidationError = error;
        ComponentValidator.getInstance(gradingConfigPathInput.getTextField()).ifPresent(ComponentValidator::revalidate);
    }
}
