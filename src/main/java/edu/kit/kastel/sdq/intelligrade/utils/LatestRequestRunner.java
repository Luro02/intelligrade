/* Licensed under EPL-2.0 2026. */
package edu.kit.kastel.sdq.intelligrade.utils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import edu.kit.kastel.sdq.artemis4j.ArtemisNetworkException;
import org.jspecify.annotations.NonNull;

/**
 * This class provides an easy to use api to correctly fetch and update the UI on the right threads.
 * <p>
 * A common pattern is to request some data (e.g. a network call) and then update the UI with the results.
 * The UI thread (EDT) must not be blocked, a background thread should be used for this.
 * In addition to that, it implements event deduplication, discarding outdated events to prevent them from influencing the UI.
 */
public final class LatestRequestRunner {
    private static final Logger LOG = Logger.getInstance(ArtemisUtils.class);

    private final Project project;
    private final RequestCounter requests;

    public LatestRequestRunner(@NonNull Project project) {
        this.project = project;
        this.requests = new RequestCounter();
    }

    public void invalidate() {
        requests.next();
    }

    public <T> RequestBuilder<T> fetchArtemis(ArtemisSupplier<? extends T> supplier) {
        return new RequestBuilder<T>(supplier::call, requests, project)
                .handle(ArtemisNetworkException.class)
                .withErrorNotification();
    }

    public <T> RequestBuilder<T> fetch(FallibleSupplier<? extends T> supplier) {
        return new RequestBuilder<T>(supplier, requests, project).withoutErrorNotification();
    }

    public static final class RequestBuilder<T> {
        private final Project project;
        private final FallibleSupplier<? extends T> supplier;
        private final RequestCounter requests;
        private Function<? super Exception, String> buildErrorMessage;
        private Consumer<? super Exception> onFailureInEdt;
        private @NonNull List<Class<? extends Exception>> exceptions;
        private boolean showErrorNotification;
        private boolean showErrorLog;

        private RequestBuilder(FallibleSupplier<? extends T> supplier, RequestCounter requests, Project project) {
            this.project = project;
            this.supplier = supplier;
            this.requests = requests;
            this.exceptions = List.of();
            this.showErrorNotification = false;
            this.showErrorLog = true;
        }

        @SafeVarargs
        public final RequestBuilder<T> handle(Class<? extends Exception>... exceptions) {
            this.exceptions = Arrays.asList(exceptions);

            return this;
        }

        public RequestBuilder<T> withErrorNotification() {
            this.showErrorNotification = true;

            return this;
        }

        public RequestBuilder<T> withErrorNotification(String errorMessage) {
            return this.withErrorNotification(_ -> errorMessage);
        }

        public RequestBuilder<T> withErrorNotification(Function<? super Exception, String> errorMessage) {
            this.buildErrorMessage = errorMessage;
            this.showErrorNotification = true;

            return this;
        }

        public RequestBuilder<T> withoutErrorNotification() {
            this.showErrorNotification = false;
            return this;
        }

        public RequestBuilder<T> withoutErrorLogging() {
            this.showErrorLog = false;
            return this;
        }

        public RequestBuilder<T> onFailureInEdt(Consumer<? super Exception> onFailureInEdt) {
            this.onFailureInEdt = onFailureInEdt;
            return this;
        }

        public void thenIf(BooleanSupplier stillRelevant, Consumer<? super T> apply) {
            int requestId = this.requests.next();

            Predicate<Exception> handlesException = exception -> {
                for (var handledException : this.exceptions) {
                    if (handledException.isInstance(exception)) {
                        return true;
                    }
                }

                return false;
            };

            Runnable request = () -> {
                try {
                    T result = this.supplier.call();

                    invokeOnEDTIfRelevant(requestId, stillRelevant, () -> apply.accept(result));
                } catch (Exception exception) {
                    // pass along any exception that the code does not want to handle
                    if (!handlesException.test(exception)) {
                        if (exception instanceof RuntimeException runtimeException) {
                            throw runtimeException;
                        }

                        // Checked exceptions must be handled, but the caller does not want
                        // to handle these, which is why they are wrapped in a RuntimeException
                        throw new IllegalStateException(exception);
                    }

                    if (this.showErrorLog) {
                        LOG.warn(exception);
                    }

                    if (showErrorNotification) {
                        String title = "Error";
                        if (exception instanceof ArtemisNetworkException) {
                            title = "Network Error";
                        }

                        var content = exception.getMessage();
                        if (buildErrorMessage != null) {
                            content = "%s (%s)".formatted(buildErrorMessage.apply(exception), exception.getMessage());
                        }

                        ArtemisUtils.displayGenericErrorBalloon(title, content);
                    }

                    if (onFailureInEdt != null) {
                        invokeOnEDTIfRelevant(requestId, stillRelevant, () -> onFailureInEdt.accept(exception));
                    }
                }
            };

            if (ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().executeOnPooledThread(request);
            } else {
                request.run();
            }
        }

        private void invokeOnEDTIfRelevant(int requestId, BooleanSupplier stillRelevant, Runnable action) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || !requests.isCurrent(requestId) || !stillRelevant.getAsBoolean()) {
                    return;
                }

                action.run();
            });
        }
    }

    @FunctionalInterface
    public interface ArtemisSupplier<T> {
        T call() throws ArtemisNetworkException;
    }

    @FunctionalInterface
    public interface FallibleSupplier<T> {
        T call() throws Exception;
    }

    private static class RequestCounter {
        private final AtomicInteger current = new AtomicInteger();

        int next() {
            return this.current.incrementAndGet();
        }

        boolean isCurrent(int request) {
            return this.current.get() == request;
        }
    }
}
