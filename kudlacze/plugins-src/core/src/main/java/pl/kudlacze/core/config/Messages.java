package pl.kudlacze.core.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Teksty z messages_pl.yml w formacie MiniMessage. Plik z folderu pluginu nadpisuje
 * wartości domyślne z jara, brakujące klucze biorą się z jara. Tag {@code <prefix>}
 * wstawia prefiks z klucza {@code prefix}.
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration config;
    private final Logger logger;

    public Messages(YamlConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public static Messages load(File dataFolder, InputStream defaults, Logger logger) {
        YamlConfiguration def = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaults, StandardCharsets.UTF_8));
        File file = new File(dataFolder, "messages_pl.yml");
        YamlConfiguration cfg = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        cfg.setDefaults(def);
        return new Messages(cfg, logger);
    }

    public String raw(String key) {
        String value = config.getString(key);
        if (value == null) {
            logger.warning("Brak tekstu w messages_pl.yml: " + key);
            return "<red>[" + key + "]</red>";
        }
        return value;
    }

    public List<String> rawList(String key) {
        List<String> list = config.getStringList(key);
        return list.isEmpty() ? List.of(raw(key)) : list;
    }

    public Component get(String key, TagResolver... resolvers) {
        return parse(raw(key), resolvers);
    }

    public List<Component> getList(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        for (String line : rawList(key)) {
            out.add(parse(line, resolvers));
        }
        return out;
    }

    public Component parse(String miniMessage, TagResolver... resolvers) {
        TagResolver all = TagResolver.builder()
                .resolvers(resolvers)
                .resolver(Placeholder.parsed("prefix", config.getString("prefix", "")))
                .build();
        return MM.deserialize(miniMessage, all);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    /** Placeholder z tekstem, który NIE jest interpretowany jako MiniMessage (np. nick, treść od gracza). */
    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** Placeholder z komponentem. */
    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /** Placeholder z zaufanym MiniMessage (np. nazwa rangi z configu). */
    public static TagResolver parsed(String name, String miniMessage) {
        return Placeholder.parsed(name, miniMessage);
    }
}
