package pl.kudlacze.core.util;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jednorazowe kody potwierdzające groźne operacje (nowa edycja, reset sezonu): administrator
 * musi przepisać kod w ciągu {@code ttlMillis}, co chroni przed przypadkowym wywołaniem.
 */
public final class ConfirmCodes {

    private record Pending(String code, long expires) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ConfirmCodes(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** Wydaje nowy 6-cyfrowy kod dla klucza (np. „edycja:Admin”), unieważniając poprzedni. */
    public String issue(String key, long now) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.put(key, new Pending(code, now + ttlMillis));
        return code;
    }

    /** Sprawdza i zużywa kod. Fałsz, gdy kod jest zły, wygasł albo nie został wydany. */
    public boolean consume(String key, String code, long now) {
        Pending p = pending.get(key);
        if (p == null || p.expires() < now || !p.code().equals(code)) {
            return false;
        }
        return pending.remove(key, p);
    }
}
