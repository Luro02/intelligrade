/* Licensed under EPL-2.0 2024-2026. */
package edu.kit.kastel.sdq.intelligrade.actions;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Optional;

import javax.swing.AbstractAction;

import com.intellij.DynamicBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.popup.list.ListPopupImpl;
import edu.kit.kastel.sdq.artemis4j.grading.penalty.MistakeType;
import edu.kit.kastel.sdq.intelligrade.state.ProjectState;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class AddAnnotationPopupAction extends AnAction {
    private static final Locale LOCALE = DynamicBundle.getLocale();

    @Override
    public @NonNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static Optional<ProjectState> getPluginState(@Nullable AnActionEvent event) {
        return Optional.ofNullable(AnAction.getEventProject(event)).map(ProjectState::getInstance);
    }

    @Override
    public void update(@NonNull AnActionEvent event) {
        Caret caret = event.getData(CommonDataKeys.CARET);

        var pluginState = getPluginState(event).orElse(null);
        if (pluginState == null) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }

        // if no exercise config is loaded, we cannot make annotations
        // if there is no caret we can not sensibly display a popup
        event.getPresentation().setEnabledAndVisible(caret != null && pluginState.isAssessing());
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent event) {
        Caret caret = event.getData(CommonDataKeys.CARET);

        var pluginState = getPluginState(event).orElse(null);
        if (pluginState == null) {
            return;
        }

        // if no exercise config is loaded, we cannot make annotations
        // if there is no caret we can not sensibly display a popup
        if (caret == null || !pluginState.isAssessing()) {
            return;
        }

        var assessment = pluginState.getActiveAssessment().orElseThrow();

        // in review mode, no new annotations can be created
        if (assessment.isReview()) {
            ArtemisUtils.displayInvalidReviewOperationBalloon();
            return;
        }

        // collect all mistake types that can be annotated
        var mistakeTypes = assessment.getGradingConfig().getMistakeTypes();

        var actions = new DefaultActionGroup();
        for (var mistakeType : mistakeTypes) {
            actions.add(new MistakeTypeButton(mistakeType));
        }

        // create a popup with all possible mistakes
        var popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                        "Add Annotation",
                        actions,
                        DataContext.EMPTY_CONTEXT,
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                        false);

        // Code borrowed from ListPopupImpl#createContent (line 323) to allow ctrl+enter for selection
        var listPopup = ((ListPopupImpl) popup);
        listPopup.registerAction(
                "handleSelectionCtrl", KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK, new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        popup.handleSelect(
                                true,
                                new KeyEvent(
                                        listPopup.getList(),
                                        KeyEvent.KEY_PRESSED,
                                        e.getWhen(),
                                        e.getModifiers(),
                                        KeyEvent.VK_ENTER,
                                        KeyEvent.CHAR_UNDEFINED));
                    }
                });

        popup.showInBestPositionFor(caret.getEditor());
    }

    private static class MistakeTypeButton extends AnActionButton {
        private final MistakeType mistakeType;

        public MistakeTypeButton(MistakeType mistakeType) {
            super(mistakeType.getButtonText().translateTo(LOCALE));
            this.mistakeType = mistakeType;
        }

        @Override
        public void actionPerformed(@NonNull AnActionEvent event) {
            boolean withCustomMessage =
                    event.getInputEvent() != null && event.getInputEvent().isControlDown();
            var pluginState = getPluginState(event).orElse(null);
            if (pluginState == null) {
                return;
            }

            pluginState.getActiveAssessment().orElseThrow().addAnnotationAtCaret(mistakeType, withCustomMessage);
        }

        @Override
        public @NonNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean isDumbAware() {
            return true;
        }
    }
}
