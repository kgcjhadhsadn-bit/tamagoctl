package pl.kudlacze.core.afk;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Heurystyka AFK jednego gracza (czysta logika — testy jednostkowe).
 * <ul>
 *   <li>Aktywność to ruch kamery (obrót o co najmniej {@link #MIN_ROTATION} stopnia), czat albo komenda.
 *       Samo przesuwanie gracza (strumień wody, wagonik, tłok) nie jest aktywnością — tak działają afkczarki.</li>
 *   <li>Kliknięcia (ataki wręcz) liczą się jako aktywność tylko wtedy, gdy odstępy między nimi są nieregularne.
 *       Stały rytm (współczynnik zmienności odstępów poniżej {@code macroCv} na ostatnich {@code window}
 *       kliknięciach) oznacza makro/autoklikacz przy farmie — takie kliknięcia nie przedłużają aktywności.</li>
 * </ul>
 */
public final class AfkDetector {

    /** Minimalny obrót kamery (stopnie), który uznajemy za ruch — drobne drgania to nie aktywność. */
    public static final float MIN_ROTATION = 1.5f;

    private final int window;
    private final double macroCv;
    private final Deque<Long> clicks = new ArrayDeque<>();
    private long lastActivity;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private boolean macro;

    public AfkDetector(long now, int window, double macroCv) {
        this.lastActivity = now;
        this.window = Math.max(5, window);
        this.macroCv = macroCv;
    }

    /** Obrót kamery. */
    public void rotation(long now, float yaw, float pitch) {
        if (Float.isNaN(lastYaw)) {
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        float dYaw = Math.abs(wrap(yaw - lastYaw));
        float dPitch = Math.abs(pitch - lastPitch);
        if (dYaw >= MIN_ROTATION || dPitch >= MIN_ROTATION) {
            lastYaw = yaw;
            lastPitch = pitch;
            activity(now);
        }
    }

    /** Czat albo komenda — zawsze aktywność. */
    public void chat(long now) {
        activity(now);
    }

    /** Kliknięcie (atak / użycie przedmiotu). Regularny rytm = makro. */
    public void click(long now) {
        clicks.addLast(now);
        while (clicks.size() > window + 1) {
            clicks.removeFirst();
        }
        if (clicks.size() <= window) {
            return;
        }
        macro = coefficientOfVariation() < macroCv;
        if (!macro) {
            lastActivity = Math.max(lastActivity, now);
        }
    }

    private void activity(long now) {
        lastActivity = Math.max(lastActivity, now);
    }

    /** Współczynnik zmienności odstępów między ostatnimi kliknięciami (odch. std. / średnia). */
    double coefficientOfVariation() {
        long[] ts = clicks.stream().mapToLong(Long::longValue).toArray();
        int n = ts.length - 1;
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += ts[i + 1] - ts[i];
        }
        mean /= n;
        if (mean <= 0) {
            return 0;
        }
        double var = 0;
        for (int i = 0; i < n; i++) {
            double d = (ts[i + 1] - ts[i]) - mean;
            var += d * d;
        }
        return Math.sqrt(var / n) / mean;
    }

    public long idleMillis(long now) {
        return Math.max(0, now - lastActivity);
    }

    public boolean isAfk(long now, long afkAfterMillis) {
        return idleMillis(now) >= afkAfterMillis;
    }

    public boolean isMacro() {
        return macro;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
