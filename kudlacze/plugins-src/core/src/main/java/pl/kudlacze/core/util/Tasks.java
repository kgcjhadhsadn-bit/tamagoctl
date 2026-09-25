package pl.kudlacze.core.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wykonawcy zadań: {@code async} dla bazy danych (nigdy na wątku serwera),
 * {@code sync} dla operacji na świecie i graczach. W testach oba są synchroniczne.
 */
public final class Tasks {

    private final Executor async;
    private final Executor sync;
    private final Logger logger;

    public Tasks(Executor async, Executor sync, Logger logger) {
        this.async = async;
        this.sync = sync;
        this.logger = logger;
    }

    /** Wersja do testów: wszystko wykonywane od razu w bieżącym wątku. */
    public static Tasks direct(Logger logger) {
        return new Tasks(Runnable::run, Runnable::run, logger);
    }

    public Executor async() {
        return async;
    }

    public Executor sync() {
        return sync;
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, async);
    }

    public CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, async);
    }

    public void runSync(Runnable runnable) {
        sync.execute(runnable);
    }

    /** Wynik przyszłości obsłużony na wątku serwera; błędy trafiają do onError (też na wątku serwera). */
    public <T> void thenSync(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> sync.execute(() -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                try {
                    onError.accept(cause);
                } catch (RuntimeException e) {
                    logger.log(Level.SEVERE, "Błąd w obsłudze błędu zadania", e);
                }
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    public static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while ((cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException) && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    public Logger logger() {
        return logger;
    }
}
