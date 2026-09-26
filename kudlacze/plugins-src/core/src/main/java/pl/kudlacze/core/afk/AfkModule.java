package pl.kudlacze.core.afk;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anty-AFK: gracz bez ruchu kamery, czatu i nieregularnych kliknięć przez {@code afk.po-minutach} jest AFK
 * (nie dostaje punktów za czas gry — {@link AfkStatus}), a po {@code afk.kick-po-minutach} zostaje wyrzucony
 * (ostrzeżenie minutę wcześniej). Stały rytm kliknięć (makro przy farmie) nie przedłuża aktywności, a personel
 * dostaje o nim powiadomienie. {@code kudlacze.afk.bypass} chroni przed kickiem.
 */
public final class AfkModule implements CoreModule, Listener {

    public static final String BYPASS = "kudlacze.afk.bypass";

    private final CoreContext ctx;
    private final Map<UUID, AfkDetector> detectors = new HashMap<>();
    private final Set<UUID> markedAfk = new HashSet<>();
    private final Set<UUID> warned = new HashSet<>();
    private final Set<UUID> reportedMacro = new HashSet<>();
    private long afkAfter;
    private long kickAfter;
    private long warnBefore;
    private BukkitTask ticker;

    public AfkModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "afk";
    }

    @Override
    public void enable() {
        afkAfter = ctx.config.integer("afk.po-minutach", 5) * 60_000L;
        kickAfter = ctx.config.integer("afk.kick-po-minutach", 15) * 60_000L;
        warnBefore = ctx.config.integer("afk.ostrzezenie-sekund", 60) * 1000L;
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        Bukkit.getOnlinePlayers().forEach(p -> detectors.put(p.getUniqueId(), newDetector()));
        ctx.provide(AfkStatus.class, this::isAfk);
        ctx.placeholders.register("afk", p -> p instanceof Player online && isAfk(online) ? "AFK" : "");
        ticker = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tick, 20L * 5, 20L * 5);
    }

    @Override
    public void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
        detectors.clear();
    }

    private AfkDetector newDetector() {
        return new AfkDetector(System.currentTimeMillis(), ctx.config.integer("afk.okno-klikniec", 20),
                ctx.config.decimal("afk.makro-cv", 0.03));
    }

    public boolean isAfk(Player p) {
        AfkDetector d = detectors.get(p.getUniqueId());
        return d != null && d.isAfk(System.currentTimeMillis(), afkAfter);
    }

    private AfkDetector detector(Player p) {
        return detectors.computeIfAbsent(p.getUniqueId(), k -> newDetector());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        detectors.put(event.getPlayer().getUniqueId(), newDetector());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        detectors.remove(uuid);
        markedAfk.remove(uuid);
        warned.remove(uuid);
        reportedMacro.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedOrientation()) {
            detector(event.getPlayer()).rotation(System.currentTimeMillis(), event.getTo().getYaw(), event.getTo().getPitch());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        Bukkit.getScheduler().runTask(ctx.plugin, () -> {
            if (p.isOnline()) {
                detector(p).chat(now);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        detector(event.getPlayer()).chat(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            AfkDetector d = detector(p);
            d.click(System.currentTimeMillis());
            if (d.isMacro() && reportedMacro.add(p.getUniqueId())) {
                ctx.plugin.getLogger().info("Anty-AFK: stały rytm kliknięć (możliwe makro) — " + p.getName());
                Bukkit.getOnlinePlayers().stream().filter(s -> s.hasPermission("kudlacze.personel"))
                        .forEach(s -> ctx.messages.send(s, "afk.makro-personel", Messages.text("gracz", p.getName())));
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            AfkDetector d = detector(p);
            long idle = d.idleMillis(now);
            if (idle < afkAfter) {
                if (markedAfk.remove(uuid)) {
                    ctx.messages.send(p, "afk.powrot");
                }
                warned.remove(uuid);
                continue;
            }
            if (markedAfk.add(uuid)) {
                ctx.messages.send(p, "afk.jestes-afk");
            }
            if (p.hasPermission(BYPASS) || kickAfter <= 0) {
                continue;
            }
            if (idle >= kickAfter) {
                ctx.plugin.getLogger().info("Anty-AFK: wyrzucono " + p.getName() + " (bezczynność "
                        + TimeFormat.duration(Duration.ofMillis(idle)) + ")");
                p.kick(ctx.messages.get("afk.kick", Messages.text("minuty", kickAfter / 60_000)));
            } else if (idle >= kickAfter - warnBefore && warned.add(uuid)) {
                p.showTitle(Title.title(ctx.messages.get("afk.tytul"), ctx.messages.get("afk.podtytul",
                        Messages.text("sekundy", (kickAfter - idle) / 1000))));
                ctx.messages.send(p, "afk.ostrzezenie", Messages.text("sekundy", (kickAfter - idle) / 1000));
            }
        }
    }
}
