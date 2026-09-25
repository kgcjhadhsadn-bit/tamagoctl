package pl.kudlacze.core.ranks;

import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.hooks.RankGranter;
import pl.kudlacze.core.util.Tasks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Zakup rang za KC. Zasady:
 * <ul>
 *   <li>ranga z polem {@code wymaga} wymaga posiadania poprzedniej (bezpośrednio lub w spadku),</li>
 *   <li>ponowny zakup posiadanej czasowo rangi przedłuża ją o kolejne dni,</li>
 *   <li>nie da się kupić rangi, którą gracz ma w spadku po wyższej (np. TYTAN kupujący VIP),</li>
 *   <li>rangi wygasają po czasie albo przy nowej edycji survivalu (co pierwsze).</li>
 * </ul>
 * Gdy LuckPerms nie nada grupy, KC są zwracane.
 */
public final class RankService {

    /** Powód odmowy zakupu — klucz komunikatu w messages_pl.yml (sekcja rangi). */
    public static final class RankException extends RuntimeException {
        private final String messageKey;
        private final String detail;

        public RankException(String messageKey, String detail) {
            super(messageKey + (detail == null ? "" : ": " + detail));
            this.messageKey = messageKey;
            this.detail = detail;
        }

        public String messageKey() {
            return messageKey;
        }

        public String detail() {
            return detail;
        }
    }

    public record Purchase(RankDefinition rank, Instant expires, long balanceAfter, boolean extended) {
    }

    /** Stan rangi u gracza (do menu). */
    public record Status(boolean owned, boolean inherited, Optional<Instant> expires, boolean requirementMet) {
    }

    private final Map<String, RankDefinition> ranks;
    private final RankGranter granter;
    private final KcService kc;
    private final Sql sql;
    private final Tasks tasks;
    private final Clock clock;
    private final LongSupplier edition;

    public RankService(Map<String, RankDefinition> ranks, RankGranter granter, KcService kc, Sql sql, Tasks tasks,
                       Clock clock, LongSupplier edition) {
        this.ranks = ranks;
        this.granter = granter;
        this.kc = kc;
        this.sql = sql;
        this.tasks = tasks;
        this.clock = clock;
        this.edition = edition;
    }

    public Map<String, RankDefinition> ranks() {
        return ranks;
    }

    public Optional<RankDefinition> find(String id) {
        return Optional.ofNullable(ranks.get(id.toLowerCase()));
    }

    public CompletableFuture<Status> status(UUID uuid, RankDefinition rank) {
        return granter.inheritedGroups(uuid).thenCombine(granter.directExpiry(uuid, rank.group()), (groups, expiry) -> {
            boolean has = groups.contains(rank.group());
            boolean req = rank.requires() == null || groups.contains(ranks.get(rank.requires()).group());
            return new Status(has, has && expiry.isEmpty(), expiry, req);
        });
    }

    public CompletableFuture<Purchase> purchase(UUID uuid, String rankId) {
        RankDefinition rank = ranks.get(rankId.toLowerCase());
        if (rank == null) {
            return CompletableFuture.failedFuture(new RankException("rangi.nieznana", rankId));
        }
        return granter.inheritedGroups(uuid).thenCombine(granter.directExpiry(uuid, rank.group()), Pair::new)
                .thenCompose(state -> {
                    Set<String> groups = state.groups();
                    boolean extending = state.expiry().isPresent();
                    if (groups.contains(rank.group()) && !extending) {
                        throw new RankException("rangi.juz-masz", rank.displayName());
                    }
                    if (!extending && rank.requires() != null) {
                        RankDefinition required = ranks.get(rank.requires());
                        if (!groups.contains(required.group())) {
                            throw new RankException("rangi.wymaga", required.displayName());
                        }
                    }
                    return kc.take(uuid, rank.cost(), KcService.TxType.ZAKUP_RANGI, "ranga " + rank.id())
                            .thenCompose(balance -> granter.grantTemporary(uuid, rank.group(), Duration.ofDays(rank.days()))
                                    .handle((expires, error) -> {
                                        if (error != null) {
                                            // zwrot KC — gracz nie może stracić waluty przez błąd uprawnień
                                            kc.give(uuid, rank.cost(), KcService.TxType.ZWROT, "zwrot za " + rank.id()).join();
                                            throw new RankException("rangi.blad-nadania", Tasks.unwrap(error).getMessage());
                                        }
                                        record(uuid, rank, expires);
                                        return new Purchase(rank, expires, balance, extending);
                                    }));
                });
    }

    /** Nowa edycja survivalu: odbiera wszystkim rangi gameplay (zostają personel, YouTuber, kosmetyka). */
    public CompletableFuture<Integer> expireAllForNewEdition() {
        return granter.revokeFromEveryone(ranks.values().stream().map(RankDefinition::group).toList());
    }

    private void record(UUID uuid, RankDefinition rank, Instant expires) {
        sql.update("INSERT INTO core_rangi (uuid, ranga, koszt, kupiono, wygasa, edycja) VALUES (?, ?, ?, ?, ?, ?)",
                uuid, rank.id(), rank.cost(), clock.millis(), expires.toEpochMilli(), (int) edition.getAsLong());
    }

    private record Pair(Set<String> groups, Optional<Instant> expiry) {
    }

    Tasks tasks() {
        return tasks;
    }
}
