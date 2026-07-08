/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.state;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import edu.kit.kastel.sdq.artemis4j.ArtemisClientException;
import edu.kit.kastel.sdq.artemis4j.client.ArtemisInstance;
import edu.kit.kastel.sdq.artemis4j.grading.ArtemisConnection;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisCredentialsProvider;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.listeners.ArtemisConnectionListener;
import edu.kit.kastel.sdq.intelligrade.login.ArtemisBrowserLoginSession;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;

@Service(Service.Level.APP)
public final class ArtemisConnectionService {
    public static final String JWT_COOKIE_KEY = "jwt";

    private static final Logger LOG = Logger.getInstance(ArtemisConnectionService.class);

    private final AtomicInteger connectionAttempts = new AtomicInteger();
    private volatile ArtemisConnectionState state = new ArtemisConnectionState.Disconnected();

    /**
     * Logs out while displaying a warning if an assessment is still running in any open project.
     */
    public void logout() {
        if (ArtemisConnectionService.hasActiveAssessment()) {
            boolean hasConfirmed = MessageDialogBuilder.okCancel(
                            "Logging out while assessing!",
                            "Logging out while assessing will discard current changes. Continue?")
                    .guessWindowAndAsk();

            if (!hasConfirmed) {
                return;
            }
        }

        connectionAttempts.incrementAndGet();
        ArtemisConnectionService.clearProjectStates();
        clearConnectionState();
        this.updateState(new ArtemisConnectionState.Disconnected());
    }

    private static boolean hasActiveAssessment() {
        return Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(project -> !project.isDisposed())
                .map(ProjectState::getInstance)
                .anyMatch(ProjectState::isAssessing);
    }

    private static void clearProjectStates() {
        for (var project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                ProjectState.getInstance(project).clearProjectSessionState();
            }
        }
    }

    private static void clearConnectionState() {
        ArtemisCredentialsProvider.getInstance().setJwt(null);
        ArtemisCredentialsProvider.getInstance().setArtemisPassword(null);
        ArtemisSettingsState.getInstance().setJwtExpiry(null);

        String artemisUrl = ArtemisSettingsState.getInstance().getArtemisInstanceUrl();
        if (!JBCefApp.isSupported()) {
            return;
        }

        JBCefBrowserBase.getGlobalJBCefCookieManager().deleteCookies(artemisUrl, JWT_COOKIE_KEY);
    }

    public ArtemisConnectionState getState() {
        return state;
    }

    private void updateState(ArtemisConnectionState newState) {
        this.state = newState;

        ApplicationManager.getApplication().invokeLater(() -> {
            ApplicationManager.getApplication()
                    .getMessageBus()
                    .syncPublisher(ArtemisConnectionListener.TOPIC)
                    .connectionStateChanged(newState);
        });
    }

    public void connect() {
        ArtemisConnectionService.clearProjectStates();
        int attemptId = connectionAttempts.incrementAndGet();
        this.updateState(new ArtemisConnectionState.Connecting());

        var settings = ArtemisSettingsState.getInstance();
        var credentials = ArtemisCredentialsProvider.getInstance();

        String url = settings.getArtemisInstanceUrl();
        if (!ArtemisUtils.doesUrlExist(url)) {
            ArtemisUtils.displayGenericErrorBalloon(
                    "Artemis URL not reachable",
                    "The Artemis URL is not valid, or you do not have a working internet connection.");
            this.updateState(new ArtemisConnectionState.Failed("Artemis URL is not reachable"));
            return;
        }

        var instance = new ArtemisInstance(settings.getArtemisInstanceUrl());

        ApplicationManager.getApplication()
                .executeOnPooledThread(() -> connectInBackground(attemptId, settings, credentials, instance));
    }

    @RequiresBackgroundThread
    private void connectInBackground(
            int attemptId,
            ArtemisSettingsState settings,
            ArtemisCredentialsProvider credentials,
            ArtemisInstance instance) {
        try {
            ArtemisConnection newConnection;
            if (settings.isUseTokenLogin()) {
                newConnection = ArtemisConnection.fromToken(instance, retrieveJWT(settings, credentials));
            } else {
                newConnection = ArtemisConnection.connectWithUsernamePassword(
                        instance, settings.getUsername(), credentials.getArtemisPassword());
            }

            verifyLogin(newConnection);
            if (isCurrentAttempt(attemptId)) {
                updateState(new ArtemisConnectionState.Connected(newConnection));
            }
        } catch (ArtemisBrowserLoginSession.LoginCancelledException exception) {
            if (isCurrentAttempt(attemptId)) {
                updateState(new ArtemisConnectionState.Disconnected());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (isCurrentAttempt(attemptId)) {
                updateState(new ArtemisConnectionState.Disconnected());
            }
        } catch (ArtemisClientException | RuntimeException exception) {
            if (!isCurrentAttempt(attemptId)) {
                return;
            }

            LOG.warn(exception);
            ArtemisUtils.displayGenericErrorBalloon("Artemis Login failed", exception.getMessage());
            updateState(new ArtemisConnectionState.Failed(exception.getMessage()));
        }
    }

    private boolean isCurrentAttempt(int attemptId) {
        return connectionAttempts.get() == attemptId;
    }

    @RequiresBackgroundThread
    private static String retrieveJWT(ArtemisSettingsState settings, ArtemisCredentialsProvider credentials)
            throws InterruptedException, ArtemisBrowserLoginSession.LoginCancelledException {
        String previousJwt = credentials.getJwt();
        if (previousJwt != null && !previousJwt.isBlank()) {
            return previousJwt;
        }

        if (!JBCefApp.isSupported()) {
            throw new IllegalStateException("JCEF unavailable");
        }

        var cookie = new ArtemisBrowserLoginSession(settings.getArtemisInstanceUrl(), previousJwt).requestJwtCookie();

        credentials.setJwt(cookie.getValue());
        settings.setJwtExpiry(cookie.getExpires());
        return cookie.getValue();
    }

    @RequiresBackgroundThread
    private static void verifyLogin(ArtemisConnection connection) throws ArtemisClientException {
        // This triggers a request and forces a connection error if the token is invalid
        connection.getAssessor();
    }
}
