/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.state;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import edu.kit.kastel.sdq.artemis4j.grading.Annotation;
import edu.kit.kastel.sdq.intelligrade.extensions.guis.AnnotationsListPanel;

@Service(Service.Level.PROJECT)
public final class AnnotationSelectionService {
    private AnnotationsListPanel panel;

    public void registerPanel(AnnotationsListPanel panel, Disposable parentDisposable) {
        this.panel = panel;
        Disposer.register(parentDisposable, () -> {
            if (this.panel == panel) {
                this.panel = null;
            }
        });
    }

    public void selectAnnotation(Annotation annotation) {
        if (panel != null) {
            panel.selectAnnotation(annotation);
        }
    }

    public static AnnotationSelectionService getInstance(Project project) {
        return project.getService(AnnotationSelectionService.class);
    }
}
