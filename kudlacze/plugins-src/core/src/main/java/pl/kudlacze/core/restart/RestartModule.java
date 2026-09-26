package pl.kudlacze.core.restart;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.Schedules;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Codzienny restart (domyślnie 05:00 Europe/Warsaw, godzinę po backupie 04:00) z ostrzeżeniami
 * 15/5/1 min na czacie i w tytule. Serwer się wyłącza, a Docker ({@code restart: unless-stopped})
 * uruchamia go ponownie.
 */
public final class RestartModule implements CoreModule {

    private final CoreContext ctx;
    private final Set<String> announced = new HashSet<>();
    private Instant next;
    private BukkitTask ticker;
    private boolean restarting;

    public RestartModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "restart";
    }

    @Override
    public void enable() {
        next = nextRestart(Instant.now(ctx.clock));
        ctx.placeholders.register("restart_za", p -> TimeFormat.duration(Duration.between(Instant.now(ctx.clock), next)));
        ticker = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tick, 20L * 5, 20L * 5);
        ctx.plugin.getLogger().info("Codzienny restart: " + next);
    }

    @Override
    public void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
    }

    Instant nextRestart(Instant now) {
        return Schedules.nextDaily(now, ctx.config.zone(), LocalTime.parse(ctx.config.string("restart.godzina", "05:00")));
    }

    private void tick() {
        if (restarting) {
            return;
        }
        Instant now = Instant.now(ctx.clock);
        long seconds = Duration.between(now, next).toSeconds();
        List<Integer> warnings = ctx.config.raw().getIntegerList("restart.ostrzezenia-minut");
        for (int warn : warnings.isEmpty() ? List.of(15, 5, 1) : warnings) {
            long at = warn * 60L;
            if (seconds <= at && seconds > at - 60 && announced.add(next + ":" + warn)) {
                Bukkit.broadcast(ctx.messages.get("restart.ostrzezenie", Messages.text("minuty", warn)));
                Title title = Title.title(ctx.messages.get("restart.tytul"),
                        ctx.messages.get("restart.podtytul", Messages.text("minuty", warn)));
                Bukkit.getOnlinePlayers().forEach(p -> p.showTitle(title));
            }
        }
        if (seconds <= 0) {
            restarting = true;
            ctx.plugin.getLogger().info("Codzienny restart serwera.");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.kick(ctx.messages.get("restart.kick"));
            }
            Bukkit.getScheduler().runTaskLater(ctx.plugin, () -> {
                Bukkit.savePlayers();
                Bukkit.shutdown();
            }, 40L);
        }
    }
}
