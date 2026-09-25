package pl.kudlacze.core.dragon;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DragonRewardsTest {

    private static final List<Long> POINTS = List.of(100L, 80L, 60L, 40L, 20L);
    private static final List<Long> KC = List.of(10L, 8L, 6L, 4L, 2L);

    @Test
    void topFiveGetPointsKcAndTitleByDamage() {
        DragonRewards t = new DragonRewards();
        String[] names = {"A", "B", "C", "D", "E", "F", "G"};
        double[] dmg = {50, 120, 30, 90, 200, 15, 60};
        UUID[] ids = new UUID[names.length];
        for (int i = 0; i < names.length; i++) {
            ids[i] = UUID.randomUUID();
            // obrażenia w dwóch porcjach — sumowanie
            t.record(ids[i], names[i], dmg[i] / 2);
            t.record(ids[i], names[i], dmg[i] / 2);
        }
        List<DragonRewards.Reward> r = DragonRewards.compute(t.ranking(), POINTS, KC, 5, 10);
        assertEquals(5, r.size());
        assertEquals(List.of("E", "B", "D", "G", "A"), r.stream().map(DragonRewards.Reward::name).toList());
        assertEquals(List.of(100L, 80L, 60L, 40L, 20L), r.stream().map(DragonRewards.Reward::points).toList());
        assertEquals(List.of(10L, 8L, 6L, 4L, 2L), r.stream().map(DragonRewards.Reward::kc).toList());
        assertTrue(r.stream().allMatch(DragonRewards.Reward::title));
        assertEquals(200, r.getFirst().damage(), 1e-9);
    }

    @Test
    void playersBelowMinimumDamageAreSkippedAndPlacesShiftUp() {
        DragonRewards t = new DragonRewards();
        t.record(UUID.randomUUID(), "Alt", 1);
        t.record(UUID.randomUUID(), "Gracz", 40);
        List<DragonRewards.Reward> r = DragonRewards.compute(t.ranking(), POINTS, KC, 1, 10);
        assertEquals(1, r.size());
        assertEquals("Gracz", r.getFirst().name());
        assertEquals(1, r.getFirst().place());
        assertEquals(100, r.getFirst().points());
    }

    @Test
    void titleOnlyForConfiguredPlaces() {
        DragonRewards t = new DragonRewards();
        t.record(UUID.randomUUID(), "A", 100);
        t.record(UUID.randomUUID(), "B", 50);
        List<DragonRewards.Reward> r = DragonRewards.compute(t.ranking(), POINTS, KC, 1, 0);
        assertTrue(r.get(0).title());
        assertFalse(r.get(1).title());
    }

    @Test
    void tieBreaksByName() {
        DragonRewards t = new DragonRewards();
        t.record(UUID.randomUUID(), "Zenek", 50);
        t.record(UUID.randomUUID(), "Adam", 50);
        assertEquals("Adam", t.ranking().getFirst().name());
    }
}
