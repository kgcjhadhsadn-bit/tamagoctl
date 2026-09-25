package pl.kudlacze.core.ranks;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;
import pl.kudlacze.core.currency.KcRepository;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.hooks.RankGranter;
import pl.kudlacze.core.util.Tasks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");

    /** Uproszczony LuckPerms: drabinka dziedziczy w dół (tytan → uvip → … → vip). */
    static final class FakeGranter implements RankGranter {
        final Map<UUID, Map<String, Instant>> direct = new HashMap<>();
        final Map<String, String> parent = Map.of("svip", "vip", "mvip", "svip", "uvip", "mvip", "tytan", "uvip");
        boolean failNext;

        @Override
        public CompletableFuture<Set<String>> inheritedGroups(UUID uuid) {
            Set<String> out = new HashSet<>();
            for (String g : direct.getOrDefault(uuid, Map.of()).keySet()) {
                String cur = g;
                while (cur != null) {
                    out.add(cur);
                    cur = parent.get(cur);
                }
            }
            out.add("default");
            return CompletableFuture.completedFuture(out);
        }

        @Override
        public CompletableFuture<Optional<Instant>> directExpiry(UUID uuid, String group) {
            return CompletableFuture.completedFuture(Optional.ofNullable(direct.getOrDefault(uuid, Map.of()).get(group)));
        }

        @Override
        public CompletableFuture<Instant> grantTemporary(UUID uuid, String group, Duration duration) {
            if (failNext) {
                failNext = false;
                return CompletableFuture.failedFuture(new IllegalStateException("LP offline"));
            }
            Map<String, Instant> m = direct.computeIfAbsent(uuid, k -> new HashMap<>());
            Instant base = m.getOrDefault(group, NOW);
            Instant exp = base.plus(duration);
            m.put(group, exp);
            return CompletableFuture.completedFuture(exp);
        }

        @Override
        public CompletableFuture<Void> revoke(UUID uuid, String group) {
            direct.getOrDefault(uuid, new HashMap<>()).remove(group);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Integer> revokeFromEveryone(Collection<String> groups) {
            int n = 0;
            for (Map<String, Instant> m : direct.values()) {
                if (m.keySet().removeAll(groups)) {
                    n++;
                }
            }
            return CompletableFuture.completedFuture(n);
        }
    }

    private FakeGranter granter;
    private KcService kc;
    private RankService ranks;
    private Sql sql;
    private final UUID player = UUID.randomUUID();

    static Map<String, RankDefinition> ladder() {
        Map<String, RankDefinition> m = new LinkedHashMap<>();
        m.put("vip", new RankDefinition("vip", "VIP", "vip", 80, null, 30, Material.EMERALD, List.of()));
        m.put("svip", new RankDefinition("svip", "SVIP", "svip", 80, "vip", 30, Material.EMERALD, List.of()));
        m.put("mvip", new RankDefinition("mvip", "MVIP", "mvip", 80, "svip", 30, Material.EMERALD, List.of()));
        m.put("uvip", new RankDefinition("uvip", "UVIP", "uvip", 80, "mvip", 30, Material.EMERALD, List.of()));
        m.put("tytan", new RankDefinition("tytan", "TYTAN", "tytan", 80, "uvip", 30, Material.EMERALD, List.of()));
        m.put("drwal", new RankDefinition("drwal", "DRWAL", "drwal", 40, null, 30, Material.GOLDEN_AXE, List.of()));
        return m;
    }

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh();
        Tasks tasks = Tasks.direct(Logger.getLogger("test"));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        kc = new KcService(new KcRepository(sql), tasks, clock, "survival");
        granter = new FakeGranter();
        ranks = new RankService(ladder(), granter, kc, sql, tasks, clock, () -> 3);
    }

    private Throwable failure(String rank) {
        CompletionException ex = assertThrows(CompletionException.class, () -> ranks.purchase(player, rank).join());
        return ex.getCause();
    }

    @Test
    void buyingVipCostsKcAndGrants30Days() {
        kc.give(player, 100, KcService.TxType.QUEST, "q").join();
        RankService.Purchase p = ranks.purchase(player, "vip").join();
        assertEquals(20, p.balanceAfter());
        assertEquals(NOW.plus(Duration.ofDays(30)), p.expires());
        assertFalse(p.extended());
        assertEquals(1, sql.query("SELECT edycja FROM core_rangi WHERE uuid = ?", rs -> rs.getInt(1), player).size());
        assertEquals(3, sql.query("SELECT edycja FROM core_rangi WHERE uuid = ?", rs -> rs.getInt(1), player).getFirst());
    }

    @Test
    void ladderRequiresPreviousRank() {
        kc.give(player, 500, KcService.TxType.QUEST, "q").join();
        RankService.RankException e = assertInstanceOf(RankService.RankException.class, failure("svip"));
        assertEquals("rangi.wymaga", e.messageKey());
        assertEquals(500L, kc.balance(player).join(), "odmowa nie może pobrać KC");
        ranks.purchase(player, "vip").join();
        ranks.purchase(player, "svip").join();
        assertTrue(granter.inheritedGroups(player).join().containsAll(List.of("vip", "svip")));
    }

    @Test
    void notEnoughKcIsRejected() {
        kc.give(player, 79, KcService.TxType.QUEST, "q").join();
        assertInstanceOf(KcService.InsufficientKcException.class, failure("vip"));
        assertTrue(granter.direct.isEmpty());
    }

    @Test
    void rebuyingExtendsDuration() {
        kc.give(player, 160, KcService.TxType.QUEST, "q").join();
        ranks.purchase(player, "vip").join();
        RankService.Purchase second = ranks.purchase(player, "vip").join();
        assertTrue(second.extended());
        assertEquals(NOW.plus(Duration.ofDays(60)), second.expires());
        assertEquals(0L, kc.balance(player).join());
    }

    @Test
    void cannotBuyRankInheritedFromHigherOne() {
        granter.direct.put(player, new HashMap<>(Map.of("tytan", NOW.plus(Duration.ofDays(10)))));
        kc.give(player, 100, KcService.TxType.QUEST, "q").join();
        RankService.RankException e = assertInstanceOf(RankService.RankException.class, failure("vip"));
        assertEquals("rangi.juz-masz", e.messageKey());
    }

    @Test
    void drwalIsIndependentOfLadder() {
        kc.give(player, 40, KcService.TxType.QUEST, "q").join();
        ranks.purchase(player, "drwal").join();
        assertTrue(granter.inheritedGroups(player).join().contains("drwal"));
    }

    @Test
    void failedGrantRefundsKc() {
        kc.give(player, 80, KcService.TxType.QUEST, "q").join();
        granter.failNext = true;
        RankService.RankException e = assertInstanceOf(RankService.RankException.class, failure("vip"));
        assertEquals("rangi.blad-nadania", e.messageKey());
        assertEquals(80L, kc.balance(player).join());
    }

    @Test
    void newEditionRevokesGameplayRanks() {
        kc.give(player, 200, KcService.TxType.QUEST, "q").join();
        ranks.purchase(player, "vip").join();
        ranks.purchase(player, "drwal").join();
        assertEquals(1, ranks.expireAllForNewEdition().join());
        assertEquals(Set.of("default"), granter.inheritedGroups(player).join());
    }

    @Test
    void loadRejectsUnknownRequirement() {
        var yml = new org.bukkit.configuration.file.YamlConfiguration();
        yml.set("rangi.svip.koszt", 80);
        yml.set("rangi.svip.wymaga", "brak");
        assertThrows(IllegalArgumentException.class, () -> RankDefinition.load(yml.getConfigurationSection("rangi")));
    }
}
