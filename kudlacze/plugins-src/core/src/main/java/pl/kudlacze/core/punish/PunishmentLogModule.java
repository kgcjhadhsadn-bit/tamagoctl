package pl.kudlacze.core.punish;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.TimeFormat;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Logi kar na Discordzie (#logi-kar). Kary nakłada LibertyBans na dowolnym serwerze sieci i zapisuje je we wspólnej
 * bazie — ten moduł (włączony na jednym serwerze z DiscordSRV) odczytuje nowe wpisy z widoku
 * {@code libertybans_simple_history} (typy: 0 BAN, 1 MUTE, 2 WARN, 3 KICK; czasy w sekundach, koniec 0 = na zawsze)
 * i ogłasza je przez DiscordSRV. Adresy IP nigdy nie trafiają na Discorda.
 */
public final class PunishmentLogModule implements CoreModule {

    static final String KEY_LAST = "kary.ostatnie-id";
    private static final UUID CONSOLE = new UUID(0, 0);

    public record Punishment(long id, int type, int victimType, UUID victim, UUID operator, String reason,
                             long start, long end) {
    }

    private final CoreContext ctx;
    private BukkitTask poller;
    private volatile boolean failed;

    public PunishmentLogModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "logi-kar";
    }

    @Override
    public void enable() {
        long period = ctx.config.integer("logi-kar.co-ile-sekund", 15) * 20L;
        poller = Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> {
            if (!failed) {
                ctx.tasks.thenSync(ctx.tasks.supplyAsync(this::fetchNew), list -> list.forEach(this::announce),
                        e -> {
                            failed = true;
                            ctx.plugin.getLogger().warning("Logi kar wyłączone — brak tabel LibertyBans? " + e.getMessage());
                        });
            }
        }, 20L * 10, period);
    }

    @Override
    public void disable() {
        if (poller != null) {
            poller.cancel();
        }
    }

    /** Nowe kary od ostatnio ogłoszonej. Przy pierwszym uruchomieniu zapamiętuje tylko bieżący stan (bez historii). */
    List<Punishment> fetchNew() {
        long max = ctx.sql.one("SELECT COALESCE(MAX(id), 0) FROM libertybans_simple_history", rs -> rs.getLong(1)).orElse(0L);
        long last = ctx.kv.getLong(KEY_LAST, -1);
        if (last < 0) {
            ctx.kv.put(KEY_LAST, String.valueOf(max));
            return List.of();
        }
        if (max <= last) {
            return List.of();
        }
        List<Punishment> list = ctx.sql.query("SELECT id, type, victim_type, victim_uuid, operator, reason, start, end "
                + "FROM libertybans_simple_history WHERE id > ? ORDER BY id LIMIT 50", rs -> new Punishment(rs.getLong(1),
                rs.getInt(2), rs.getInt(3), uuid(rs.getBytes(4)), uuid(rs.getBytes(5)), rs.getString(6), rs.getLong(7),
                rs.getLong(8)), last);
        if (!list.isEmpty()) {
            ctx.kv.put(KEY_LAST, String.valueOf(list.getLast().id()));
        }
        return list;
    }

    private void announce(Punishment p) {
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new String[]{name(p.victim()), name(p.operator())}),
                names -> ctx.discord.announce("logi-kar", format(p, p.victimType() == 0 ? names[0] : null, names[1])),
                e -> ctx.plugin.getLogger().warning("Logi kar: " + e.getMessage()));
    }

    private String name(UUID uuid) {
        if (uuid == null || CONSOLE.equals(uuid)) {
            return "Konsola";
        }
        return ctx.players.nameOf(uuid).orElse(uuid.toString().substring(0, 8));
    }

    static UUID uuid(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }

    /** Tekst na Discorda. {@code victimName} null = kara na adres IP (nie ujawniamy). */
    static String format(Punishment p, String victimName, String operatorName) {
        String type = switch (p.type()) {
            case 0 -> ":hammer: **BAN**";
            case 1 -> ":mute: **WYCISZENIE**";
            case 2 -> ":warning: **OSTRZEŻENIE**";
            case 3 -> ":boot: **KICK**";
            default -> "**KARA**";
        };
        String who = victimName == null ? "adres IP (ukryty)" : victimName;
        StringBuilder sb = new StringBuilder(type).append(" — ").append(who);
        if (p.type() == 0 || p.type() == 1) {
            sb.append(p.end() <= 0 ? " — na zawsze" : " — na " + TimeFormat.duration(Duration.ofSeconds(p.end() - p.start())));
        }
        if (p.reason() != null && !p.reason().isBlank()) {
            sb.append(" — powód: ").append(p.reason().replace("@", "@​"));
        }
        sb.append(" (nałożył: ").append(operatorName).append(")");
        return sb.toString();
    }
}
