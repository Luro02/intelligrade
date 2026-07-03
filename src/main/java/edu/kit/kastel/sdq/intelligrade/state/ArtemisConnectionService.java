/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import edu.kit.kastel.sdq.artemis4j.ArtemisClientException;
import edu.kit.kastel.sdq.artemis4j.ArtemisNetworkException;
import edu.kit.kastel.sdq.artemis4j.client.ArtemisInstance;
import edu.kit.kastel.sdq.artemis4j.grading.ArtemisConnection;
import edu.kit.kastel.sdq.artemis4j.grading.User;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisCredentialsProvider;
import edu.kit.kastel.sdq.intelligrade.extensions.settings.ArtemisSettingsState;
import edu.kit.kastel.sdq.intelligrade.login.CefUtils;
import edu.kit.kastel.sdq.intelligrade.utils.ArtemisUtils;

@Service(Service.Level.APP)
public final class ArtemisConnectionService {
    private static final Logger LOG = Logger.getInstance(ArtemisConnectionService.class);

    private final List<Consumer<ArtemisConnection>> connectionListeners = new ArrayList<>();
    private ArtemisConnection connection;

    /**
     * Logs out while displaying a warning if an assessment is still running in any open project.
     */
    public void logout() {
        if (!this.confirmLogoutIfAssessmentIsActive()) {
            return;
        }

        this.clearProjectStates();
        this.clearConnectionState();
        this.notifyListeners();
    }

    private boolean confirmLogoutIfAssessmentIsActive() {
        if (!this.hasActiveAssessment()) {
            return true;
        }

        return MessageDialogBuilder.okCancel(
                        "Logging out while assessing!",
                        "Logging out while assessing will discard current changes. Continue?")
                .guessWindowAndAsk();
    }

    private boolean hasActiveAssessment() {
        return Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(project -> !project.isDisposed())
                .map(ProjectState::getInstance)
                .anyMatch(ProjectState::isAssessing);
    }

    private void clearProjectStates() {
        for (var project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                ProjectState.getInstance(project).clearProjectSessionState();
            }
        }
    }

    private void clearConnectionState() {
        this.resetState();

        ArtemisCredentialsProvider.getInstance().setJwt(null);
        ArtemisCredentialsProvider.getInstance().setArtemisPassword(null);
        ArtemisSettingsState.getInstance().setJwtExpiry(null);

        if (JBCefApp.isSupported()) {
            CefUtils.resetCookies();
        }
    }

    public void listenForChange(Consumer<ArtemisConnection> listener, Disposable parentDisposable) {
        this.connectionListeners.add(listener);
        Disposer.register(parentDisposable, () -> this.connectionListeners.remove(listener));
        listener.accept(this.connection);
    }

    private void notifyListeners() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Consumer<ArtemisConnection> listener : this.connectionListeners) {
                listener.accept(this.connection);
            }
        });
    }

    public void connect() {
        this.clearProjectStates();
        this.resetState();

        var settings = ArtemisSettingsState.getInstance();
        var credentials = ArtemisCredentialsProvider.getInstance();

        String url = settings.getArtemisInstanceUrl();
        if (!ArtemisUtils.doesUrlExist(url)) {
            ArtemisUtils.displayGenericErrorBalloon(
                    "Artemis URL not reachable",
                    "The Artemis URL is not valid, or you do not have a working internet connection.");
            this.notifyListeners();
            return;
        }

        var instance = new ArtemisInstance(settings.getArtemisInstanceUrl());

        CompletableFuture<ArtemisConnection> connectionFuture;
        if (settings.isUseTokenLogin()) {
            connectionFuture = retrieveJWT().thenApplyAsync(token -> ArtemisConnection.fromToken(instance, token));
        } else {
            connectionFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return ArtemisConnection.connectWithUsernamePassword(
                            instance, settings.getUsername(), credentials.getArtemisPassword());
                } catch (ArtemisClientException e) {
                    throw new CompletionException(e);
                }
            });
        }

        connectionFuture
                .thenAcceptAsync(newConnection -> {
                    this.connection = newConnection;
                    try {
                        this.verifyLogin();
                        this.notifyListeners();
                    } catch (ArtemisClientException e) {
                        throw new CompletionException(e);
                    }
                })
                .exceptionallyAsync(e -> {
                    LOG.warn(e);
                    ArtemisUtils.displayGenericErrorBalloon("Artemis Login failed", e.getMessage());
                    this.connection = null;
                    this.notifyListeners();
                    return null;
                });
    }

    private CompletableFuture<String> retrieveJWT() {
        var settings = ArtemisSettingsState.getInstance();
        var credentials = ArtemisCredentialsProvider.getInstance();

        return CompletableFuture.supplyAsync(() -> {
            String previousJwt = credentials.getJwt();
            if (previousJwt != null && !previousJwt.isBlank()) {
                return previousJwt;
            }

            if (!JBCefApp.isSupported()) {
                throw new CompletionException(new IllegalStateException("JCEF unavailable"));
            }

            try {
                var cookie = CefUtils.jcefBrowserLogin().get();
                credentials.setJwt(cookie.getValue());
                settings.setJwtExpiry(cookie.getExpires());
                return cookie.getValue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CompletionException(ex);
            } catch (ExecutionException e) {
                throw new CompletionException(e);
            }
        });
    }

    private void verifyLogin() throws ArtemisClientException {
        // This triggers a request and forces a connection error if the token is invalid
        this.connection.getAssessor();
    }

    private void resetState() {
        this.connection = null;
    }

    public User getAssessor() throws ArtemisNetworkException {
        return this.connection.getAssessor();
    }
}
