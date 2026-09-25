package pl.kudlacze.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tekst wpisany przez gracza (czat, tabliczki) z formatowaniem dozwolonym przez uprawnienia:
 * <ul>
 *   <li>{@link Level#PLAIN} — brak formatowania (wszystkie tagi są wyświetlane dosłownie),</li>
 *   <li>{@link Level#COLORS} — kolory i style (&amp;a, &amp;l, &lt;red&gt;, &lt;bold&gt;…),</li>
 *   <li>{@link Level#GRADIENTS} — dodatkowo gradienty, tęcza i kolory HEX.</li>
 * </ul>
 * Tagi interaktywne (click/hover/insertion) nigdy nie są dozwolone.
 */
public final class PlayerText {

    public enum Level { PLAIN, COLORS, GRADIENTS }

    private static final Map<Character, String> LEGACY = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"), Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"), Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"), Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"), Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"), Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"), Map.entry('l', "<bold>"), Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"), Map.entry('r', "<reset>"));
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Pattern HEX_TAG = Pattern.compile("<#[0-9a-fA-F]{6}>|<color:#[0-9a-fA-F]{6}>");

    private static final MiniMessage PLAIN = MiniMessage.builder().tags(TagResolver.empty()).build();
    private static final MiniMessage COLORS = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.reset())).build();
    private static final MiniMessage GRADIENTS = MiniMessage.builder().tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.reset(),
            StandardTags.gradient(), StandardTags.rainbow(), StandardTags.transition())).build();

    private PlayerText() {
    }

    public static Component format(String input, Level level) {
        return switch (level) {
            case PLAIN -> Component.text(input);
            case COLORS -> COLORS.deserialize(stripHex(legacyToMini(input, false)));
            case GRADIENTS -> GRADIENTS.deserialize(legacyToMini(input, true));
        };
    }

    /** Zamienia kody &amp; na tagi MiniMessage (&amp;k — „magiczny” tekst — jest pomijany). */
    static String legacyToMini(String input, boolean allowHex) {
        String s = input;
        if (allowHex) {
            s = LEGACY_HEX.matcher(s).replaceAll("<#$1>");
        } else {
            s = LEGACY_HEX.matcher(s).replaceAll("");
        }
        Matcher m = LEGACY_CODE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            char c = Character.toLowerCase(m.group(1).charAt(0));
            m.appendReplacement(out, Matcher.quoteReplacement(LEGACY.getOrDefault(c, "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Poziom COLORS nie dopuszcza kolorów HEX (zarezerwowane dla rangi z gradientami). */
    private static String stripHex(String s) {
        return HEX_TAG.matcher(s).replaceAll("");
    }

    /** Nieużywane — zachowane dla czytelności: parser bez żadnych tagów. */
    static MiniMessage plainParser() {
        return PLAIN;
    }
}
