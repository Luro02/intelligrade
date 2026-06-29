/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.extensions.tool_windows;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import edu.kit.kastel.sdq.intelligrade.extensions.guis.AssessmentPanel;
import edu.kit.kastel.sdq.intelligrade.extensions.guis.ExercisePanel;
import edu.kit.kastel.sdq.intelligrade.extensions.guis.TestCasePanel;
import org.jspecify.annotations.NonNull;

/**
 * This class handles all logic for the main grading UI.
 * It does not handle any other logic, that should be factored out.
 */
public class MainToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NonNull Project project, @NonNull ToolWindow toolWindow) {
        toolWindow.getContentManager().addContent(createExerciseContent(project, toolWindow));
        toolWindow.getContentManager().addContent(createGradingContent(project));
        toolWindow.getContentManager().addContent(createTestResultsContent(project));
    }

    private static Content createExerciseContent(@NonNull Project project, ToolWindow toolWindow) {
        var disposable = Disposer.newDisposable("IntelliGrade Exercise Panel");
        var content = ContentFactory.getInstance()
                .createContent(new ExercisePanel(project, toolWindow, disposable), "Exercise", false);
        content.setDisposer(disposable);
        return content;
    }

    private static Content createGradingContent(@NonNull Project project) {
        var disposable = Disposer.newDisposable("IntelliGrade Grading Panel");
        var content =
                ContentFactory.getInstance().createContent(new AssessmentPanel(disposable, project), "Grading", false);
        content.setDisposer(disposable);
        return content;
    }

    private static Content createTestResultsContent(@NonNull Project project) {
        var disposable = Disposer.newDisposable("IntelliGrade Test Results Panel");
        var content = ContentFactory.getInstance()
                .createContent(new TestCasePanel(disposable, project), "Test Results", false);
        content.setDisposer(disposable);
        return content;
    }
}
