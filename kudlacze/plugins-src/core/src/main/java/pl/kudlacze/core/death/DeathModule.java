package pl.kudlacze.core.death;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * „Śmierć = kick” w stylu Brodatych. Tryby (config {@code smierc.tryb}):
 * <ul>
 *   <li>{@code KICK} — wyrzucenie z serwera (Velocity przenosi gracza do lobby),</li>
 *   <li>{@code KICK_AND_LOCK} — jak wyżej + blokada ponownego wejścia na N minut,</li>
 *   <li>{@code DISABLED} — wyłączone.</li>
 * </ul>
 * Uprawnienie {@code kudlacze.death.nokick} (UVIP+ i personel) omija kick i blokadę; nie dotyczy też
 * światów z listy {@code smierc.swiaty-bez-kicka} (arena Smoka Kudłatego).
 */
public final class DeathModule implements CoreModule, Listener {

    public enum Mode { KICK, KICK_AND_LOCK, DISABLED }

    public static final String BYPASS = "kudlacze.death.nokick";

    private final CoreContext ctx;
    private Mode mode;
    private Duration lock;

    public DeathModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "smierc";
    }

    @Override
    public void enable() {
        mode = Mode.valueOf(ctx.config.string("smierc.tryb", "KICK").toUpperCase(Locale.ROOT));
        lock = Duration.ofMinutes(ctx.config.integer("smierc.blokada-minut", 5));
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
    }

    public Mode mode() {
        return mode;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (mode == Mode.DISABLED || player.hasPermission(BYPASS)
                || ctx.config.list("smierc.swiaty-bez-kicka").contains(player.getWorld().getName())) {
            return;
        }
        long until = ctx.clock.millis() + lock.toMillis();
        if (mode == Mode.KICK_AND_LOCK) {
            UUID uuid = player.getUniqueId();
            ctx.tasks.runAsync(() -> saveLock(uuid, until));
        }
        // kick w następnym ticku — śmierć (drop, wiadomość) musi się najpierw przetworzyć
        Bukkit.getScheduler().runTask(ctx.plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            String key = mode == Mode.KICK_AND_LOCK ? "smierc.kick-blokada" : "smierc.kick";
            player.kick(ctx.messages.get(key, Messages.text("czas", TimeFormat.duration(lock))));
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (mode != Mode.KICK_AND_LOCK || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Optional<Long> until = lockedUntil(event.getUniqueId());
        long now = ctx.clock.millis();
        if (until.isPresent() && until.get() > now) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ctx.messages.get("smierc.blokada-trwa",
                            Messages.text("czas", TimeFormat.duration(Duration.ofMillis(until.get() - now)))));
        }
    }

    void saveLock(UUID uuid, long until) {
        String server = ctx.server();
        int updated = ctx.sql.update("UPDATE core_blokady_smierci SET do_kiedy = ? WHERE uuid = ? AND serwer = ?",
                until, uuid, server);
        if (updated == 0) {
            ctx.sql.update("INSERT INTO core_blokady_smierci (uuid, serwer, do_kiedy) VALUES (?, ?, ?)",
                    uuid, server, until);
        }
    }

    Optional<Long> lockedUntil(UUID uuid) {
        return ctx.sql.one("SELECT do_kiedy FROM core_blokady_smierci WHERE uuid = ? AND serwer = ?",
                rs -> rs.getLong(1), uuid, ctx.server());
    }
}
