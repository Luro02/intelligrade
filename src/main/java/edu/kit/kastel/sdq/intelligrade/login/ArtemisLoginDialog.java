/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.login;

import java.awt.BorderLayout;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class ArtemisLoginDialog extends DialogWrapper {
    private final JBCefBrowser browser;
    private final Runnable onDisposed;

    ArtemisLoginDialog(@Nullable Project project, JBCefBrowser browser, Runnable onDisposed) {
        super(project, false, IdeModalityType.MODELESS);

        this.browser = browser;
        this.onDisposed = onDisposed;

        setTitle("Artemis Login");
        init();
    }

    @RequiresEdt
    @Override
    protected @NonNull JComponent createCenterPanel() {
        JPanel browserContainer = new JPanel(new BorderLayout());
        browserContainer.add(browser.getComponent(), BorderLayout.CENTER);
        return browserContainer;
    }

    @RequiresEdt
    @Override
    protected Action @NonNull [] createActions() {
        return new Action[] {getCancelAction()};
    }

    @RequiresEdt
    @Override
    protected void dispose() {
        try {
            onDisposed.run();
        } finally {
            browser.dispose();
            super.dispose();
        }
    }
}
