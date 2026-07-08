/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.login;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefCookie;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import edu.kit.kastel.sdq.intelligrade.state.ArtemisConnectionService;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jspecify.annotations.Nullable;

public final class ArtemisBrowserLoginSession {
    private static final Logger LOG = Logger.getInstance(ArtemisBrowserLoginSession.class);
    private static final long COOKIE_CHECK_INTERVAL_MS = 500L;

    private final String artemisUrl;
    private final @Nullable String previousJwt;
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean completed = new AtomicBoolean();

    private volatile @Nullable JBCefCookie jwtCookie;
    private volatile @Nullable Throwable failure;
    private volatile @Nullable ScheduledFuture<?> cookieCheck;
    private @Nullable ArtemisLoginDialog dialog;

    public ArtemisBrowserLoginSession(String artemisUrl, @Nullable String previousJwt) {
        this.artemisUrl = artemisUrl;
        this.previousJwt = previousJwt;
    }

    private static void rethrow(Throwable cause) throws LoginCancelledException {
        if (cause instanceof LoginCancelledException loginCancelledException) {
            throw loginCancelledException;
        }

        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        if (cause instanceof Error error) {
            throw error;
        }

        throw new IllegalStateException(cause);
    }

    @RequiresBackgroundThread
    public JBCefCookie requestJwtCookie() throws InterruptedException, LoginCancelledException {
        try {
            SwingUtilities.invokeAndWait(this::open);
        } catch (InvocationTargetException exception) {
            rethrow(exception.getCause());
        }

        finished.await();

        if (jwtCookie == null) {
            rethrow(failure);
        }

        return jwtCookie;
    }

    @RequiresEdt
    private void open() {
        var browser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build();
        browser.getJBCefClient().addLoadHandler(new JwtCookieLoadHandler(this), browser.getCefBrowser());

        this.dialog = new ArtemisLoginDialog(
                null, browser, () -> cancel(new LoginCancelledException("Artemis login was cancelled")));

        this.cookieCheck = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(
                        this::tryCompleteFromCookies, 0L, COOKIE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        browser.loadURL(artemisUrl);
        dialog.show();
    }

    @RequiresBackgroundThread
    private void tryCompleteFromCookies() {
        if (completed.get()) {
            return;
        }

        try {
            var cookies = JBCefBrowserBase.getGlobalJBCefCookieManager()
                    .getCookies(artemisUrl, true)
                    .get();
            for (var cookie : cookies) {
                if (ArtemisConnectionService.JWT_COOKIE_KEY.equals(cookie.getName())
                        && !Objects.equals(cookie.getValue(), previousJwt)) {
                    complete(cookie);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(new LoginCancelledException("Artemis login was interrupted"));
        } catch (ExecutionException | RuntimeException exception) {
            LOG.debug(exception);
        }
    }

    private void complete(JBCefCookie cookie) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        jwtCookie = cookie;
        stopCookieChecks();
        finished.countDown();
        SwingUtilities.invokeLater(() -> {
            if (dialog != null && !dialog.isDisposed()) {
                dialog.close(DialogWrapper.OK_EXIT_CODE);
            }
        });
    }

    private void cancel(LoginCancelledException exception) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        failure = exception;
        stopCookieChecks();
        finished.countDown();
    }

    private void stopCookieChecks() {
        var scheduledCheck = cookieCheck;
        if (scheduledCheck != null) {
            scheduledCheck.cancel(false);
        }
    }

    private static final class JwtCookieLoadHandler extends CefLoadHandlerAdapter {
        private final ArtemisBrowserLoginSession session;

        private JwtCookieLoadHandler(ArtemisBrowserLoginSession session) {
            this.session = session;
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            AppExecutorUtil.getAppExecutorService().execute(session::tryCompleteFromCookies);
        }
    }

    public static final class LoginCancelledException extends Exception {
        private LoginCancelledException(String message) {
            super(message);
        }
    }
}
