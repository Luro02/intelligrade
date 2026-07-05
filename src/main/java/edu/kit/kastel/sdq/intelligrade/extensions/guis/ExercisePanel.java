/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.extensions.guis;

import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.JTextComponent;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.JBColor;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import edu.kit.kastel.sdq.artemis4j.ArtemisNetworkException;
import edu.kit.kastel.sdq.artemis4j.grading.ArtemisConnection;
import edu.kit.kastel.sdq.artemis4j.grading.Course;
import edu.kit.kastel.sdq.artemis4j.grading.Exam;
import edu.kit.kastel.sdq.artemis4j.grading.ProgrammingExercise;
import edu.kit.kastel.sdq.artemis4j.grading.User;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.GradingConfig;
import edu.kit.kastel.sdq.intelligrade.listeners.ExerciseListener;
import edu.kit.kastel.sdq.intelligrade.state.ArtemisConnectionService;
import edu.kit.kastel.sdq.intelligrade.state.ProjectState;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;
import edu.kit.kastel.sdq.intelligrade.utils.RequestCounter;
import edu.kit.kastel.sdq.intelligrade.widgets.TextBuilder;
import net.miginfocom.swing.MigLayout;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ExercisePanel extends SimpleToolWindowPanel {
    private static final Logger LOG = Logger.getInstance(ExercisePanel.class);

    private final Project project;

    private final JTextComponent connectedLabel;
    private final ComboBox<Course> courseSelector;
    private final ComboBox<OptionalExam> examSelector;
    private final ComboBox<ProgrammingExercise> exerciseSelector;
    private @Nullable ArtemisConnection activeConnection;
    private boolean updatingSelectors;

    // The handlers are invoked asynchronously, these keep track of the current request to filter
    // out old ones that are no longer relevant.
    private final RequestCounter connectionRequestId = new RequestCounter();
    private final RequestCounter courseRequestId = new RequestCounter();
    private final RequestCounter examRequestId = new RequestCounter();
    private final RequestCounter compatibleExerciseRequestId = new RequestCounter();

    /**
     * Returns a {@link ComboBox} that resizes with the parent window.
     * <br>
     * The default stops resizing at a relatively large size.
     *
     * @param <T> the type of the combo box items
     * @return the combo box
     */
    private static <T> ComboBox<T> createWrappingComboBox() {
        return new ComboBox<>(0);
    }

    public ExercisePanel(Disposable parentDisposable, @NonNull Project project, ToolWindow toolWindow) {
        super(true, true);

        this.project = project;

        this.connectedLabel = TextBuilder.immutable("")
                .horizontalAlignment(TextBuilder.Alignment.CENTER)
                .text();

        // Why must this be wrapped in a ScrollablePanel?
        // The content panel is later wrapped in a JScrollPane, to support scrolling when the window is too small.
        // By default, the JScrollPane does not allow to shrink components below their preferred size,
        // but this is necessary for the components to shrink properly (much better than having to use a scroll bar).
        //
        // -> If your component is not shrinking properly, it is likely because of a JScrollPane
        JPanel content = new ScrollablePanel(new MigLayout("wrap 2", "[][grow]"));
        content.add(connectedLabel, "span 2, grow");

        content.add(new JBLabel("Course:"));
        courseSelector = createWrappingComboBox();
        courseSelector.addItemListener(this::handleCourseSelected);
        content.add(courseSelector, "growx");

        content.add(new JBLabel("Exam:"));
        examSelector = createWrappingComboBox();
        examSelector.addItemListener(this::handleExamSelected);
        content.add(examSelector, "growx");

        content.add(new JBLabel("Exercise:"));
        exerciseSelector = createWrappingComboBox();
        exerciseSelector.addItemListener(this::handleExerciseSelected);
        content.add(exerciseSelector, "growx");

        content.add(new StatisticsPanel(parentDisposable, project), "span 2, growx");

        content.add(new TitledSeparator("General"), "spanx 2, growx");
        content.add(new GeneralPanel(parentDisposable, project), "span 2, growx");

        content.add(new TitledSeparator("Assessment"), "spanx 2, growx");
        content.add(new AssessmentActionsPanel(parentDisposable, this.project), "spanx 2, growx");

        content.add(new TitledSeparator("Backlog"), "spanx 2, growx");
        content.add(new BacklogPanel(parentDisposable, project), "spanx 2, growx");

        setContent(new JScrollPane(
                content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));

        ApplicationManager.getApplication()
                .getService(ArtemisConnectionService.class)
                .listenForChange(this::handleConnectionChange, parentDisposable);

        // The config can define which exercise it is allowed for. intelligrade automatically selects one
        // based on that if the currently selected one is not allowed or none is selected.
        //
        // To update the combo box, we need to be notified of this:
        ProjectState.getInstance(project).subscribe(parentDisposable, new ExerciseListener() {
            @Override
            public void configChanged(GradingConfig.@Nullable GradingConfigDTO config) {
                ExercisePanel.this.selectExerciseForConfig(config);
            }
        });
    }

    static JButton createWrappingButton(String text) {
        return new JButton("<html><body style='text-align: center;'>" + text + "</body></html>");
    }

    @RequiresEdt
    private void handleExerciseSelected(ItemEvent event) {
        if (updatingSelectors || event.getStateChange() == ItemEvent.DESELECTED) {
            return;
        }

        publishExerciseChanged((ProgrammingExercise) event.getItem());
    }

    @RequiresEdt
    private void publishExerciseChanged(@Nullable ProgrammingExercise exercise) {
        if (project.isDisposed()) {
            return;
        }

        project.getMessageBus().syncPublisher(ExerciseListener.TOPIC).exerciseChanged(exercise);
    }

    @RequiresEdt
    private void handleExamSelected(ItemEvent event) {
        if (updatingSelectors || event.getStateChange() == ItemEvent.DESELECTED) {
            return;
        }

        var selectedCourse = (Course) courseSelector.getSelectedItem();
        if (selectedCourse == null) {
            return;
        }

        loadExercises(selectedCourse, (OptionalExam) event.getItem(), getLoadedGradingConfig());
    }

    @RequiresEdt
    private void loadExercises(
            Course selectedCourse, OptionalExam selectedExam, GradingConfig.@Nullable GradingConfigDTO config) {
        int requestId = this.examRequestId.next();
        setExerciseItems(List.of());
        publishExerciseChanged(null);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<ProgrammingExercise> exercises;
            try {
                exercises = selectedExam.exercises(selectedCourse);
            } catch (ArtemisNetworkException ex) {
                LOG.warn(ex);
                ArtemisUtils.displayNetworkErrorBalloon("Failed to fetch exercise info", ex);
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()
                        || !this.examRequestId.isCurrent(requestId)
                        || examSelector.getSelectedItem() != selectedExam
                        || courseSelector.getSelectedItem() != selectedCourse) {
                    return;
                }

                setExerciseItems(exercises);
                selectExerciseForConfig(config);
                updateUI();
            });
        });
    }

    private static <T extends ProgrammingExercise> List<T> sortExercises(List<T> exercises) {
        List<T> result = new ArrayList<>(exercises);

        result.sort((a, b) -> CharSequence.compare(a.getTitle(), b.getTitle()));

        return result;
    }

    @RequiresEdt
    private void handleCourseSelected(ItemEvent event) {
        if (updatingSelectors || event.getStateChange() == ItemEvent.DESELECTED) {
            return;
        }

        loadCourse((Course) event.getItem());
    }

    @RequiresEdt
    private void loadCourse(Course course) {
        int requestId = this.courseRequestId.next();
        clearExamItems();
        setExerciseItems(List.of());
        publishExerciseChanged(null);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Exam> exams;
            try {
                exams = course.getExams();
            } catch (ArtemisNetworkException ex) {
                LOG.warn(ex);
                ArtemisUtils.displayNetworkErrorBalloon("Failed to fetch exam info", ex);
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                // sanity check given that there is no guarantee when this lambda will be executed,
                // it might happen much later in which case it should not influence the current ui
                if (project.isDisposed()
                        || !this.courseRequestId.isCurrent(requestId)
                        || courseSelector.getSelectedItem() != course) {
                    return;
                }

                setExamItems(exams);
                selectExamAndExerciseForConfig(course, getLoadedGradingConfig());
                updateUI();
            });
        });
    }

    private GradingConfig.@Nullable GradingConfigDTO getLoadedGradingConfig() {
        return ProjectState.getInstance(this.project)
                .getLoadedGradingConfigDTO()
                .orElse(null);
    }

    @RequiresEdt
    private void selectExamAndExerciseForConfig(Course course, GradingConfig.@Nullable GradingConfigDTO config) {
        compatibleExerciseRequestId.next();

        var selectedExam = (OptionalExam) examSelector.getSelectedItem();
        if (selectedExam == null) {
            publishExerciseChanged(null);
            return;
        }

        if (config == null) {
            loadExercises(course, selectedExam, null);
            return;
        }

        findAndSelectCompatibleExercise(course, config);
    }

    @RequiresEdt
    private void selectExerciseForConfig(GradingConfig.@Nullable GradingConfigDTO config) {
        compatibleExerciseRequestId.next();

        if (exerciseSelector.getItemCount() == 0) {
            publishExerciseChanged(null);
            return;
        }

        if (config == null) {
            publishSelectedExercise();
            return;
        }

        var selectedExercise = (ProgrammingExercise) exerciseSelector.getSelectedItem();
        if (selectedExercise != null && config.isAllowedForExercise(selectedExercise.getId())) {
            publishSelectedExercise();
            return;
        }

        for (int i = 0; i < exerciseSelector.getItemCount(); i++) {
            var exercise = exerciseSelector.getItemAt(i);
            if (config.isAllowedForExercise(exercise.getId())) {
                selectExerciseAt(i);
                return;
            }
        }

        var selectedCourse = (Course) courseSelector.getSelectedItem();
        if (selectedCourse != null) {
            findAndSelectCompatibleExercise(selectedCourse, config);
        }
    }

    @RequiresEdt
    private void findAndSelectCompatibleExercise(Course course, GradingConfig.GradingConfigDTO config) {
        int requestId = this.compatibleExerciseRequestId.next();
        var exams = getExamItems();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            OptionalExam compatibleExam;
            try {
                compatibleExam = findCompatibleExam(course, exams, config);
            } catch (ArtemisNetworkException ex) {
                LOG.warn(ex);
                ArtemisUtils.displayNetworkErrorBalloon("Failed to fetch exercise info", ex);
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()
                        || !this.compatibleExerciseRequestId.isCurrent(requestId)
                        || courseSelector.getSelectedItem() != course) {
                    return;
                }

                if (compatibleExam == null) {
                    publishExerciseChanged(null);
                    return;
                }

                selectExam(compatibleExam);
                loadExercises(course, compatibleExam, config);
            });
        });
    }

    @RequiresBackgroundThread
    private static @Nullable OptionalExam findCompatibleExam(
            Course course, Iterable<OptionalExam> exams, GradingConfig.GradingConfigDTO config)
            throws ArtemisNetworkException {
        for (var exam : exams) {
            for (var exercise : exam.exercises(course)) {
                if (config.isAllowedForExercise(exercise.getId())) {
                    return exam;
                }
            }
        }

        return null;
    }

    @RequiresEdt
    private List<OptionalExam> getExamItems() {
        List<OptionalExam> exams = new ArrayList<>();
        for (int i = 0; i < examSelector.getItemCount(); i++) {
            exams.add(examSelector.getItemAt(i));
        }
        return exams;
    }

    @RequiresEdt
    private void setExamItems(List<Exam> exams) {
        updateSelectors(() -> {
            examSelector.removeAllItems();
            examSelector.addItem(new OptionalExam(null));
            for (var exam : exams) {
                examSelector.addItem(new OptionalExam(exam));
            }
        });
    }

    @RequiresEdt
    private void clearExamItems() {
        updateSelectors(examSelector::removeAllItems);
    }

    @RequiresEdt
    private void setExerciseItems(List<ProgrammingExercise> exercises) {
        updateSelectors(() -> {
            exerciseSelector.removeAllItems();
            for (var exercise : exercises) {
                exerciseSelector.addItem(exercise);
            }
        });
    }

    @RequiresEdt
    private void selectExam(OptionalExam exam) {
        updateSelectors(() -> examSelector.setSelectedItem(exam));
    }

    @RequiresEdt
    private void selectExerciseAt(int index) {
        updateSelectors(() -> exerciseSelector.setSelectedIndex(index));
        publishSelectedExercise();
    }

    @RequiresEdt
    private void publishSelectedExercise() {
        publishExerciseChanged((ProgrammingExercise) exerciseSelector.getSelectedItem());
    }

    @RequiresEdt
    private void updateSelectors(Runnable update) {
        updatingSelectors = true;
        try {
            update.run();
        } finally {
            updatingSelectors = false;
        }
    }

    @RequiresEdt
    private void handleConnectionChange(ArtemisConnection connection) {
        activeConnection = connection;
        int requestId = connectionRequestId.next();
        updateSelectors(courseSelector::removeAllItems);
        clearExamItems();
        setExerciseItems(List.of());
        publishExerciseChanged(null);

        if (connection != null) {
            connectedLabel.setText("⌛ Loading...");
            connectedLabel.setForeground(JBColor.YELLOW);
            ApplicationManager.getApplication()
                    .executeOnPooledThread(() -> loadConnectionCourses(connection, requestId));
            updateUI();
            return;
        }

        connectedLabel.setText("❌ Not connected");
        connectedLabel.setForeground(JBColor.RED);
        updateUI();
    }

    @RequiresBackgroundThread
    private void loadConnectionCourses(ArtemisConnection connection, int requestId) {
        User assessor;
        List<Course> courses;
        try {
            assessor = connection.getAssessor();
            courses = connection.getCourses();
        } catch (ArtemisNetworkException ex) {
            LOG.warn(ex);
            ArtemisUtils.displayNetworkErrorBalloon("Failed to fetch course info", ex);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()
                    || !this.connectionRequestId.isCurrent(requestId)
                    || activeConnection != connection) {
                return;
            }

            connectedLabel.setText("✔ Connected to %s as %s"
                    .formatted(connection.getClient().getInstance().getDomain(), assessor.getLogin()));
            connectedLabel.setForeground(JBColor.GREEN);
            updateSelectors(() -> {
                for (Course course : courses) {
                    courseSelector.addItem(course);
                }
            });
            var selectedCourse = (Course) courseSelector.getSelectedItem();
            if (selectedCourse != null) {
                loadCourse(selectedCourse);
            }
            updateUI();
        });
    }

    private record OptionalExam(Exam exam) {
        public List<ProgrammingExercise> exercises(Course course) throws ArtemisNetworkException {
            if (exam == null) {
                return sortExercises(course.getProgrammingExercises());
            }

            List<ProgrammingExercise> result = new ArrayList<>();
            for (var group : exam.getExerciseGroups()) {
                result.addAll(sortExercises(group.getProgrammingExercises()));
            }

            return result;
        }

        @Override
        public @NonNull String toString() {
            return exam == null ? "<No Exam>" : exam.toString();
        }
    }
}
