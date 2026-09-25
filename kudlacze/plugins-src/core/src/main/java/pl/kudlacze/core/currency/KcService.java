package pl.kudlacze.core.currency;

import pl.kudlacze.core.util.Tasks;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logika Kłaków Czarodzieja (KC): saldo wirtualne, nagrody, zakupy, przelewy.
 * Wszystkie operacje na bazie są asynchroniczne; ostatnio znane salda są w pamięci
 * (dla placeholderów w TAB/menu).
 */
public final class KcService {

    /** Typy wpisów w logu transakcji. */
    public enum TxType {
        NAGRODA_CODZIENNA, QUEST, WYZWANIE, SMOK, RANKING_SEZONU, KONKURS, ZAKUP_RANGI, ZWROT, ADMIN, INNE
    }

    /** Brak wystarczającej liczby KC. */
    public static final class InsufficientKcException extends RuntimeException {
        private final long balance;

        public InsufficientKcException(long balance) {
            super("Za mało Kłaków Czarodzieja (saldo: " + balance + ")");
            this.balance = balance;
        }

        public long balance() {
            return balance;
        }
    }

    private final KcRepository repo;
    private final Tasks tasks;
    private final Clock clock;
    private final String server;
    private final Map<UUID, Long> cache = new ConcurrentHashMap<>();

    public KcService(KcRepository repo, Tasks tasks, Clock clock, String server) {
        this.repo = repo;
        this.tasks = tasks;
        this.clock = clock;
        this.server = server;
    }

    public CompletableFuture<Long> balance(UUID uuid) {
        return tasks.supplyAsync(() -> remember(uuid, repo.balance(uuid)));
    }

    /** Ostatnie znane saldo (0, gdy jeszcze nie wczytano). */
    public long cachedBalance(UUID uuid) {
        return cache.getOrDefault(uuid, 0L);
    }

    public void forget(UUID uuid) {
        cache.remove(uuid);
    }

    public CompletableFuture<Long> give(UUID uuid, long amount, TxType type, String source) {
        requirePositive(amount);
        return tasks.supplyAsync(() -> {
            OptionalLong result = repo.apply(uuid, amount, type.name(), source, server, now());
            return remember(uuid, result.orElseThrow());
        });
    }

    public CompletableFuture<Long> take(UUID uuid, long amount, TxType type, String source) {
        requirePositive(amount);
        return tasks.supplyAsync(() -> {
            OptionalLong result = repo.apply(uuid, -amount, type.name(), source, server, now());
            if (result.isEmpty()) {
                throw new InsufficientKcException(remember(uuid, repo.balance(uuid)));
            }
            return remember(uuid, result.getAsLong());
        });
    }

    public CompletableFuture<Long> set(UUID uuid, long value, String source) {
        if (value < 0) {
            throw new IllegalArgumentException("Saldo nie może być ujemne");
        }
        return tasks.supplyAsync(() -> remember(uuid, repo.set(uuid, value, source, server, now())));
    }

    public CompletableFuture<Void> transfer(UUID from, UUID to, long amount) {
        requirePositive(amount);
        if (from.equals(to)) {
            throw new IllegalArgumentException("Nie można przelać KC samemu sobie");
        }
        return tasks.runAsync(() -> {
            if (!repo.transfer(from, to, amount, server, now())) {
                throw new InsufficientKcException(remember(from, repo.balance(from)));
            }
            remember(from, repo.balance(from));
            remember(to, repo.balance(to));
        });
    }

    public CompletableFuture<List<KcRepository.Transaction>> history(UUID uuid, int limit) {
        return tasks.supplyAsync(() -> repo.history(uuid, limit));
    }

    /** Wypłata na przedmioty: zakłada nową partię i zwraca jej identyfikator. */
    public CompletableFuture<UUID> withdraw(UUID uuid, long amount) {
        requirePositive(amount);
        UUID batch = UUID.randomUUID();
        return tasks.supplyAsync(() -> {
            OptionalLong result = repo.withdrawToBatch(uuid, amount, batch, server, now());
            if (result.isEmpty()) {
                throw new InsufficientKcException(remember(uuid, repo.balance(uuid)));
            }
            remember(uuid, result.getAsLong());
            return batch;
        });
    }

    /** Wpłata przedmiotów z danej partii (nadwyżka ponad pozostałe KC partii = duplikat, nie jest uznawana). */
    public CompletableFuture<java.util.Optional<KcRepository.Deposit>> deposit(UUID uuid, UUID batchId, long amount) {
        requirePositive(amount);
        return tasks.supplyAsync(() -> {
            var result = repo.depositFromBatch(uuid, batchId, amount, server, now());
            result.ifPresent(d -> remember(uuid, d.balanceAfter()));
            return result;
        });
    }

    private long remember(UUID uuid, long value) {
        cache.put(uuid, value);
        return value;
    }

    private long now() {
        return clock.millis();
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Kwota musi być dodatnia");
        }
    }
}
