package pl.kudlacze.core.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dostęp do config.yml. Wartości w postaci {@code ${CFG_NAZWA}} (gdy obraz dockera ich nie podstawił)
 * są rozwiązywane z właściwości systemowych albo zmiennych środowiskowych o tej nazwie.
 */
public final class CoreConfig {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_]+)}$");

    private final FileConfiguration cfg;

    public CoreConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public FileConfiguration raw() {
        return cfg;
    }

    public String string(String path, String def) {
        return resolve(cfg.getString(path, def));
    }

    public int integer(String path, int def) {
        String s = string(path, null);
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return cfg.getInt(path, def);
        }
    }

    public long longValue(String path, long def) {
        return cfg.isSet(path) ? cfg.getLong(path, def) : def;
    }

    public double decimal(String path, double def) {
        return cfg.isSet(path) ? cfg.getDouble(path, def) : def;
    }

    public boolean bool(String path, boolean def) {
        return cfg.getBoolean(path, def);
    }

    public List<String> list(String path) {
        return cfg.getStringList(path);
    }

    public ConfigurationSection section(String path) {
        return cfg.getConfigurationSection(path);
    }

    public boolean moduleEnabled(String id) {
        return cfg.getBoolean("moduly." + id, false);
    }

    public String server() {
        String s = string("serwer", "nieznany");
        return s == null || s.isBlank() ? "nieznany" : s;
    }

    public ZoneId zone() {
        return ZoneId.of(string("strefa-czasowa", "Europe/Warsaw"));
    }

    public static String resolve(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(value.trim());
        if (!m.matches()) {
            return value;
        }
        String name = m.group(1);
        String fromProp = System.getProperty(name);
        if (fromProp != null) {
            return fromProp;
        }
        String fromEnv = System.getenv(name);
        return fromEnv != null ? fromEnv : value;
    }
}
