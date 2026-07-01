/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.state;

import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import edu.kit.kastel.sdq.artemis4j.grading.Annotation;
import edu.kit.kastel.sdq.artemis4j.grading.CorrectionRound;
import edu.kit.kastel.sdq.artemis4j.grading.PackedAssessment;
import edu.kit.kastel.sdq.artemis4j.grading.ProgrammingExercise;
import edu.kit.kastel.sdq.artemis4j.grading.ProgrammingSubmission;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.GradingConfig;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.InvalidGradingConfigException;
import edu.kit.kastel.sdq.intelligrade.AssessmentTracker;
import edu.kit.kastel.sdq.intelligrade.EndAssessmentService;
import edu.kit.kastel.sdq.intelligrade.ReopenAssessmentService;
import edu.kit.kastel.sdq.intelligrade.StartAssessmentService;
import edu.kit.kastel.sdq.intelligrade.SubmitAction;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;

@Service(Service.Level.PROJECT)
public final class ProjectState {
    private static final Logger LOG = Logger.getInstance(ProjectState.class);

    private final List<Consumer<ProgrammingExercise>> exerciseSelectedListeners = new ArrayList<>();
    private final List<Consumer<ActiveAssessment>> assessmentStartedListeners = new ArrayList<>();
    private final List<Runnable> assessmentClosedListeners = new ArrayList<>();
    private final List<Consumer<GradingConfig.GradingConfigDTO>> gradingConfigChangedListeners = new ArrayList<>();

    private final Project project;
    private ProgrammingExercise activeExercise;
    private GradingConfig.GradingConfigDTO cachedGradingConfigDTO;

    private ActiveAssessment activeAssessment;

    public ProjectState(Project project) {
        this.project = project;

        // The code for opening/closing assessments is in kotlin, but this class keeps track of the active assessment
        // as well.
        //
        // With this, PluginState will be notified when an assessment changes.
        AssessmentTracker.getInstance(project).addListener(changedAssessment -> {
            activeAssessment = changedAssessment;

            // The invokeLater ensures that the listeners are running on EDT, which is required for UI updates.
            if (changedAssessment == null) {
                // Notify listeners that the assessment was closed
                for (Runnable listener : assessmentClosedListeners) {
                    ApplicationManager.getApplication().invokeLater(listener);
                }
            } else {
                // Notify listeners that the assessment was started
                for (Consumer<ActiveAssessment> listener : assessmentStartedListeners) {
                    ApplicationManager.getApplication().invokeLater(() -> listener.accept(changedAssessment));
                }
            }
        });

        // Try to parse the grading config once from storage
        getGradingConfigDTO(false);
    }

    public static ProjectState getInstance(Project project) {
        return project.getService(ProjectState.class);
    }

    public void clearProjectSessionState() {
        activeExercise = null;

        AssessmentTracker.getInstance(project).clearAssessment();
    }

    /**
     * Registers a listener that is called when the grading config changes.
     * <p>
     * This is used to update the UI when the grading config changes.
     *
     * @param listener the listener to be called
     */
    public void registerGradingConfigChangedListener(
            Consumer<GradingConfig.GradingConfigDTO> listener, Disposable parentDisposable) {
        this.gradingConfigChangedListeners.add(listener);
        Disposer.register(parentDisposable, () -> this.gradingConfigChangedListeners.remove(listener));
        if (this.cachedGradingConfigDTO != null) {
            // If the grading config is already loaded, call the listener immediately
            listener.accept(this.cachedGradingConfigDTO);
        }
    }

    public void registerExerciseSelectedListener(Consumer<ProgrammingExercise> listener, Disposable parentDisposable) {
        this.exerciseSelectedListeners.add(listener);
        Disposer.register(parentDisposable, () -> this.exerciseSelectedListeners.remove(listener));
        listener.accept(this.activeExercise);
    }

    public boolean isAssessing() {
        return activeAssessment != null;
    }

    public boolean hasActiveAssessment() {
        return isAssessing();
    }

    public void startNextAssessment(CorrectionRound correctionRound) {
        this.internalStartAssessment(correctionRound, null);
    }

    public void startAssessment(ProgrammingSubmission submission, CorrectionRound correctionRound) {
        this.internalStartAssessment(correctionRound, submission);
    }

    private void internalStartAssessment(CorrectionRound correctionRound, ProgrammingSubmission submission) {
        if (isAssessing()) {
            ArtemisUtils.displayFinishAssessmentFirstBalloon();
            return;
        }

        if (activeExercise == null) {
            ArtemisUtils.displayGenericErrorBalloon("Could not start assessment", "No course selected");
            return;
        }

        var gradingConfig = this.createGradingConfig();
        if (gradingConfig.isEmpty()) {
            return;
        }

        StartAssessmentService.getInstance(project)
                .queue(correctionRound, gradingConfig.get(), activeExercise, submission);
    }

    public void reopenAssessment(PackedAssessment assessment) {
        if (isAssessing()) {
            ArtemisUtils.displayFinishAssessmentFirstBalloon();
            return;
        }

        if (activeExercise == null) {
            ArtemisUtils.displayGenericErrorBalloon("Could not reopen assessment", "No exercise selected");
            return;
        }

        var gradingConfig = this.createGradingConfig();
        if (gradingConfig.isEmpty()) {
            return;
        }

        ReopenAssessmentService.getInstance(project).queue(assessment, gradingConfig.get());
    }

    public void updateAssessmentState(SubmitAction action) {
        if (!isAssessing()) {
            ArtemisUtils.displayNoAssessmentBalloon();
            return;
        }

        EndAssessmentService.getInstance(project).queue(action);
    }

    public void setSelectedGradingConfigPath(String path) {
        ArtemisSettingsState.getInstance().setSelectedGradingConfigPath(path);
        this.cachedGradingConfigDTO = null;
    }

    public Optional<GradingConfig.GradingConfigDTO> getGradingConfigDTO(boolean required) {
        if (this.cachedGradingConfigDTO == null) {
            var gradingConfigPath = ArtemisSettingsState.getInstance().getSelectedGradingConfigPath();
            if (gradingConfigPath == null) {
                if (required) {
                    onInvalidGradingConfig("Please select a grading config");
                }
                return Optional.empty();
            }

            try {
                var gradingConfigFile =
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(gradingConfigPath));
                if (gradingConfigFile == null) {
                    throw new IOException("Grading config file not found: " + gradingConfigPath);
                }

                var fileContent = VfsUtilCore.loadText(gradingConfigFile);
                this.cachedGradingConfigDTO = GradingConfig.readDTOFromString(fileContent);

                for (var listener : this.gradingConfigChangedListeners) {
                    listener.accept(this.cachedGradingConfigDTO);
                }
            } catch (IOException | InvalidGradingConfigException e) {
                if (required) {
                    LOG.warn(e);
                    onInvalidGradingConfig(e.getMessage());
                }
                return Optional.empty();
            }
        }
        return Optional.of(cachedGradingConfigDTO);
    }

    public boolean hasReviewConfig() {
        return this.getGradingConfigDTO(false)
                .map(GradingConfig.GradingConfigDTO::review)
                .orElse(false);
    }

    public Optional<ProgrammingExercise> getActiveExercise() {
        return Optional.ofNullable(activeExercise);
    }

    public void setActiveExercise(ProgrammingExercise exercise) {
        this.activeExercise = exercise;
        for (var listener : this.exerciseSelectedListeners) {
            listener.accept(exercise);
        }
    }

    public Optional<ActiveAssessment> getActiveAssessment() {
        return Optional.ofNullable(activeAssessment);
    }

    public void registerAssessmentStartedListener(Consumer<ActiveAssessment> listener, Disposable parentDisposable) {
        this.assessmentStartedListeners.add(listener);
        Disposer.register(parentDisposable, () -> this.assessmentStartedListeners.remove(listener));
        if (this.isAssessing()) {
            listener.accept(activeAssessment);
        }
    }

    public void registerAssessmentClosedListener(Runnable listener, Disposable parentDisposable) {
        this.assessmentClosedListeners.add(listener);
        Disposer.register(parentDisposable, () -> this.assessmentClosedListeners.remove(listener));
    }

    private void onInvalidGradingConfig(String message) {
        ArtemisUtils.displayGenericErrorBalloon("No/invalid grading config", message);
    }

    private Optional<GradingConfig> createGradingConfig() {
        var gradingConfigDTO = this.getGradingConfigDTO(true);
        if (gradingConfigDTO.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(GradingConfig.fromDTO(gradingConfigDTO.get(), activeExercise));
        } catch (InvalidGradingConfigException e) {
            LOG.warn(e);
            ArtemisUtils.displayGenericErrorBalloon("Invalid grading config", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the root directory of this project.
     * This is the directory under which the project configuration files (like .idea) are stored.
     * There are some caveats, see {@link Project#getBasePath()}.
     *
     * @return the path to the root directory of this project
     */
    public Path getProjectRootDirectory() {
        var basePath = this.project.getBasePath();
        return Path.of(Objects.requireNonNullElse(basePath, ""));
    }

    private String loadInspectionsProfile() throws IOException {
        try (var in = ProjectState.class.getResourceAsStream("/Project_Default.xml")) {
            if (in == null) {
                throw new IllegalStateException("Default inspections profile not found in resources");
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void setupProjectProfile() {
        try {
            var profileContent = loadInspectionsProfile();

            WriteAction.runAndWait(() -> {
                try {
                    var baseDirectory = ProjectUtil.guessProjectDir(project);
                    if (baseDirectory == null) {
                        throw new IOException("Project base directory is not available");
                    }

                    var profileDirectory = VfsUtil.createDirectories(
                            baseDirectory.getPath() + "/" + Project.DIRECTORY_STORE_FOLDER + "/inspectionProfiles");
                    var profileFile = profileDirectory.findOrCreateChildData(this, "Project_Default.xml");
                    VfsUtil.saveText(profileFile, profileContent);
                } catch (IOException ioException) {
                    throw new UncheckedIOException(ioException);
                }
            });
        } catch (IOException ioException) {
            throw new IllegalStateException("Could not write default profile", ioException);
        } catch (UncheckedIOException ioException) {
            throw new IllegalStateException("Could not write default profile", ioException.getCause());
        }
    }

    public Optional<VirtualFile> findAnnotationVirtualFile(Annotation annotation) {
        var baseDirectory = ProjectUtil.guessProjectDir(project);
        if (baseDirectory == null) {
            return Optional.empty();
        }

        var relativePath = ActiveAssessment.ASSIGNMENT_SUB_PATH + "/"
                + annotation.getFilePath().replace("\\", "/");
        return Optional.ofNullable(baseDirectory.findFileByRelativePath(relativePath));
    }

    public static String colorToCSS(Color color) {
        return "rgb(%d, %d, %d)".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }
}
