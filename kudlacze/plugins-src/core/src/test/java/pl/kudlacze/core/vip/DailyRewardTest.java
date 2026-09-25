package pl.kudlacze.core.vip;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyRewardTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    /** Tabela z domyślnego config.yml pluginu — ta, którą opisuje docs/EKONOMIA.md. */
    static DailyReward defaultTable() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                DailyRewardTest.class.getClassLoader().getResourceAsStream("config.yml"), StandardCharsets.UTF_8));
        return DailyReward.fromSection(cfg, "nagroda-codzienna.nagrody");
    }

    @Test
    void defaultTableGivesAboutFiveKcPerWeek() {
        double perDay = defaultTable().expectedKc();
        assertEquals(0.7, perDay, 1e-9);
        double perWeek = perDay * 7;
        assertTrue(perWeek >= 4.5 && perWeek <= 5.5, "nagrody codzienne: " + perWeek + " KC/tydz.");
    }

    @Test
    void rollingFollowsWeights() {
        DailyReward table = new DailyReward(List.of(
                new DailyReward.Entry(3, 1, 0, List.of(), "a"),
                new DailyReward.Entry(1, 0, 100, List.of(), "b")));
        Random random = new Random(42);
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 40_000; i++) {
            hits.merge(table.roll(random).description(), 1, Integer::sum);
        }
        double ratio = hits.get("a") / (double) hits.get("b");
        assertEquals(3.0, ratio, 0.15);
    }

    @Test
    void emptyOrZeroWeightTablesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DailyReward(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DailyReward(List.of(new DailyReward.Entry(0, 1, 0, List.of(), "x"))));
    }

    @Test
    void resetsAtWarsawMidnightIncludingDst() {
        // 23:30 UTC 26.09 = 01:30 czasu warszawskiego 27.09 (CEST) → reset 28.09 00:00 CEST = 27.09 22:00 UTC
        assertEquals(Instant.parse("2026-09-27T22:00:00Z"),
                DailyReward.nextReset(Instant.parse("2026-09-26T23:30:00Z"), WARSAW));
        // zmiana czasu 25.10.2026: północ 26.10 w CET = 25.10 23:00 UTC
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"),
                DailyReward.nextReset(Instant.parse("2026-10-25T12:00:00Z"), WARSAW));
    }

    @Test
    void cooldownCanBeAcquiredOncePerPeriod() {
        Cooldowns cd = new Cooldowns(TestDb.fresh());
        UUID p = UUID.randomUUID();
        long now = 1_000_000L;
        assertTrue(cd.tryAcquire(p, "nagroda", now, now + 500).isEmpty());
        OptionalLong busy = cd.tryAcquire(p, "nagroda", now + 100, now + 600);
        assertEquals(OptionalLong.of(now + 500), busy);
        assertTrue(cd.tryAcquire(p, "nagroda", now + 500, now + 1000).isEmpty(), "po upływie cooldownu znów wolne");
        assertFalse(cd.tryAcquire(p, "nagroda", now + 999, now + 2000).isEmpty());
        assertTrue(cd.tryAcquire(UUID.randomUUID(), "nagroda", now, now + 1).isEmpty(), "cooldown jest per gracz");
    }
}
