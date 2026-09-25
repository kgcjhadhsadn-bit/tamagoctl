package pl.kudlacze.core.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.PlayerText;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Czat sieci: format z prefiksem LuckPerms i tytułem, formatowanie wiadomości według uprawnień
 * ({@code kudlacze.czat.kolory}, {@code kudlacze.czat.gradienty}) oraz egzekwowanie regulaminu:
 * caps, powtarzanie tej samej wiadomości, reklama cudzych serwerów.
 */
public final class ChatModule implements CoreModule, Listener {

    public static final String PERM_COLORS = "kudlacze.czat.kolory";
    public static final String PERM_GRADIENTS = "kudlacze.czat.gradienty";
    public static final String PERM_BYPASS = "kudlacze.czat.bez-filtra";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)\\b((\\d{1,3}[.,]){3}\\d{1,3}|[a-z0-9-]{2,}\\s?[.,]\\s?(pl|eu|net|com|org|gg|me|xyz|io|cc|ovh|pro)\\b)");

    private final CoreContext ctx;
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTime = new ConcurrentHashMap<>();
    private List<String> allowedDomains;

    public ChatModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "czat";
    }

    @Override
    public void enable() {
        allowedDomains = new java.util.ArrayList<>(ctx.config.list("czat.dozwolone-domeny"));
        String domain = ctx.config.string("domena", "");
        if (domain != null && !domain.isBlank() && !domain.startsWith("${")) {
            allowedDomains.add(domain.toLowerCase(Locale.ROOT));
        }
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
        if (!player.hasPermission(PERM_BYPASS)) {
            Verdict v = check(player.getUniqueId(), raw, System.currentTimeMillis());
            if (v == Verdict.SPAM) {
                event.setCancelled(true);
                ctx.messages.send(player, "czat.spam");
                return;
            }
            if (v == Verdict.ADVERT) {
                event.setCancelled(true);
                ctx.messages.send(player, "czat.reklama");
                notifyStaff(player, raw);
                return;
            }
            if (v == Verdict.CAPS) {
                raw = raw.toLowerCase(Locale.ROOT);
            }
        }
        PlayerText.Level level = player.hasPermission(PERM_GRADIENTS) ? PlayerText.Level.GRADIENTS
                : player.hasPermission(PERM_COLORS) ? PlayerText.Level.COLORS : PlayerText.Level.PLAIN;
        Component message = PlayerText.format(raw, level);
        Component prefix = LEGACY.deserialize(ctx.meta.prefix(player));
        Component suffix = LEGACY.deserialize(ctx.meta.suffix(player));
        String titleKey = ctx.placeholders.resolve(player, "tytul_czat");
        Component title = titleKey == null || titleKey.isBlank() ? Component.empty() : ctx.messages.parse(titleKey);
        String format = ctx.config.string("czat.format", "<tytul><prefiks><nick><sufiks><dark_gray> » </dark_gray><wiadomosc>");
        Component rendered = ctx.messages.parse(format,
                Messages.component("tytul", title),
                Messages.component("prefiks", prefix),
                Messages.component("sufiks", suffix),
                Messages.text("nick", player.getName()),
                Messages.component("wiadomosc", message));
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, msg) -> rendered));
    }

    enum Verdict { OK, CAPS, SPAM, ADVERT }

    /** Czysta logika filtrów (testy jednostkowe). */
    Verdict check(UUID uuid, String raw, long now) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        String previous = lastMessage.put(uuid, normalized);
        Long prevTime = lastTime.put(uuid, now);
        long repeatWindow = ctx.config.integer("czat.powtorzenie-sekund", 5) * 1000L;
        if (previous != null && previous.equals(normalized) && prevTime != null && now - prevTime < repeatWindow) {
            return Verdict.SPAM;
        }
        if (isAdvert(normalized)) {
            return Verdict.ADVERT;
        }
        return isCaps(raw) ? Verdict.CAPS : Verdict.OK;
    }

    boolean isAdvert(String normalized) {
        return isAdvert(normalized, allowedDomains);
    }

    /** Czy tekst zawiera adres IP albo domenę spoza listy dozwolonych. */
    static boolean isAdvert(String normalized, java.util.List<String> allowedDomains) {
        var m = ADDRESS.matcher(normalized);
        while (m.find()) {
            String found = m.group().replaceAll("\\s", "").replace(',', '.');
            boolean allowed = allowedDomains.stream().anyMatch(d -> found.endsWith(d) || d.endsWith(found));
            if (!allowed) {
                return true;
            }
        }
        return false;
    }

    static boolean isCaps(String raw) {
        long letters = raw.chars().filter(Character::isLetter).count();
        if (letters < 6) {
            return false;
        }
        long upper = raw.chars().filter(Character::isUpperCase).count();
        return upper * 100 / letters >= 70;
    }

    private void notifyStaff(Player offender, String raw) {
        Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("kudlacze.personel"))
                .forEach(p -> ctx.messages.send(p, "czat.reklama-personel", Messages.text("gracz", offender.getName()),
                        Messages.text("tresc", raw)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
        lastTime.remove(event.getPlayer().getUniqueId());
    }
}
