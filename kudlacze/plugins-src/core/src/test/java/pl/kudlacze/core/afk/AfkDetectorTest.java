package pl.kudlacze.core.afk;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfkDetectorTest {

    private static final long MIN = 60_000;

    @Test
    void cameraMovementKeepsPlayerActive() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        d.rotation(0, 0, 0);
        d.rotation(4 * MIN, 30, 5);
        assertFalse(d.isAfk(8 * MIN, 5 * MIN));
        assertTrue(d.isAfk(9 * MIN + 1, 5 * MIN));
    }

    @Test
    void tinyJitterIsNotActivity() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        d.rotation(0, 10, 10);
        for (int i = 1; i <= 10; i++) {
            d.rotation(i * MIN, 10 + (i % 2) * 0.5f, 10);
        }
        assertEquals(10 * MIN, d.idleMillis(10 * MIN));
    }

    @Test
    void yawWrapAroundCountsAsSmallTurn() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        d.rotation(0, 179.5f, 0);
        d.rotation(MIN, -179.8f, 0); // 0,7° w rzeczywistości
        assertEquals(MIN, d.idleMillis(MIN));
        d.rotation(2 * MIN, -170f, 0);
        assertEquals(0, d.idleMillis(2 * MIN));
    }

    @Test
    void chatAndCommandsCount() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        d.chat(6 * MIN);
        assertFalse(d.isAfk(10 * MIN, 5 * MIN));
    }

    @Test
    void autoclickerRhythmDoesNotKeepPlayerActive() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        long t = 0;
        for (int i = 0; i < 600; i++) {
            t += 625; // co 0,625 s jak w zegarku — farma z makrem (łącznie ponad 6 minut)
            d.click(t);
        }
        assertTrue(d.isMacro());
        assertTrue(d.isAfk(t, 5 * MIN));
    }

    @Test
    void humanClickingIsActivity() {
        AfkDetector d = new AfkDetector(0, 20, 0.03);
        Random r = new Random(42);
        long t = 0;
        for (int i = 0; i < 400; i++) {
            t += 450 + r.nextInt(400); // człowiek: nieregularne odstępy
            d.click(t);
        }
        assertFalse(d.isMacro());
        assertFalse(d.isAfk(t + 1_000, 5 * MIN));
    }
}
