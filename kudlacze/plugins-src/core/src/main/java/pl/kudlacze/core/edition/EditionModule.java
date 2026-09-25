package pl.kudlacze.core.edition;

import org.bukkit.Bukkit;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.CoreConfig;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.ranks.RankService;
import pl.kudlacze.core.util.ConfirmCodes;
import pl.kudlacze.core.util.TimeFormat;
import pl.kudlacze.core.world.ResetRestart;
import pl.kudlacze.core.world.WorldReset;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Edycje survivalu: {@code /edycja nowa} → {@code /edycja potwierdz <kod>} (60 s) zamyka edycję —
 * nowy numer w bazie, wygaszenie rang gameplay (config), archiwum starego świata, reset domów i pieniędzy
 * EssentialsX, nowy świat z losowym seedem po restarcie. End jest zamknięty do eventu w 2. tygodniu
 * ({@code /edycja end otworz} albo automatycznie po {@code edycja.end.auto-otwarcie-dni}).
 */
public final class EditionModule implements CoreModule, Listener {

    public static final String PERM_ADMIN = "kudlacze.edycja.admin";
    public static final String PERM_END_BYPASS = "kudlacze.edycja.end.omin";
    public static final String KEY_NUMBER = "survival.edycja";
    public static final String KEY_START = "survival.edycja.start";
    public static final String KEY_END_OPEN = "survival.end.otwarty";

    private final CoreContext ctx;
    private final ConfirmCodes codes = new ConfirmCodes(60_000);
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private volatile boolean endOpen;
    private volatile long number = 1;
    private volatile long start;
    private volatile boolean resetting;
    private BukkitTask ticker;

    public EditionModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "edycje";
    }

    @Override
    public void enable() {
        KudlaczeCore.command(ctx, "edycja", new EditionCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.placeholders.register("end_otwarty", p -> endOpen ? "tak" : "nie");
        start = ctx.clock.millis();
        ctx.tasks.runAsync(() -> {
            if (ctx.kv.get(KEY_START).isEmpty()) {
                long now = ctx.clock.millis();
                long n = ctx.kv.getLong(KEY_NUMBER, 1);
                ctx.kv.put(KEY_NUMBER, String.valueOf(n));
                ctx.kv.put(KEY_START, String.valueOf(now));
                ctx.kv.put(KEY_END_OPEN, "false");
                recordEdition(n, now, null);
            }
            reload();
        });
        ticker = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tick, 20L * 10, 20L * 30);
    }

    @Override
    public void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
    }

    private void reload() {
        number = ctx.kv.getLong(KEY_NUMBER, 1);
        start = ctx.kv.getLong(KEY_START, ctx.clock.millis());
        endOpen = Boolean.parseBoolean(ctx.kv.get(KEY_END_OPEN).orElse("false"));
    }

    private void recordEdition(long n, long startMillis, String archive) {
        if (ctx.sql.update("UPDATE edycje SET start = ?, archiwum = ? WHERE numer = ?", startMillis, archive, n) == 0) {
            ctx.sql.update("INSERT INTO edycje (numer, start, archiwum) VALUES (?, ?, ?)", n, startMillis, archive);
        }
    }

    /** Moment automatycznego otwarcia Endu: dzień startu + N dni, o podanej godzinie. */
    public static Instant endOpening(CoreConfig c, long editionStart) {
        ZoneId zone = c.zone();
        LocalDate day = Instant.ofEpochMilli(editionStart).atZone(zone).toLocalDate()
                .plusDays(c.integer("edycja.end.auto-otwarcie-dni", 7));
        return day.atTime(LocalTime.parse(c.string("edycja.end.godzina", "20:00"))).atZone(zone).toInstant();
    }

    /** Planowany koniec edycji (start + {@code edycja.dlugosc-dni}). */
    public static Instant plannedEnd(CoreConfig c, long editionStart) {
        return Instant.ofEpochMilli(editionStart).plus(Duration.ofDays(c.integer("edycja.dlugosc-dni", 90)));
    }

    /** Okno zgłoszeń do Kudłatych Chat: ostatnie {@code edycja.konkurs-dni} dni edycji. */
    public static boolean contestOpen(CoreConfig c, long editionStart, Instant now) {
        Instant end = plannedEnd(c, editionStart);
        Instant from = end.minus(Duration.ofDays(c.integer("edycja.konkurs-dni", 14)));
        return !now.isBefore(from) && now.isBefore(end);
    }

    private void tick() {
        if (resetting || endOpen || ctx.config.integer("edycja.end.auto-otwarcie-dni", 7) <= 0) {
            return;
        }
        if (!Instant.now(ctx.clock).isBefore(endOpening(ctx.config, start))) {
            setEnd(true, null);
        }
    }

    private void setEnd(boolean open, CommandSender by) {
        endOpen = open;
        ctx.tasks.runAsync(() -> ctx.kv.put(KEY_END_OPEN, String.valueOf(open)));
        if (open) {
            Bukkit.broadcast(ctx.messages.get("edycja.end-otwarty"));
            ctx.discord.announce("czat-survival", ctx.messages.raw("edycja.discord-end"));
        }
        if (by != null) {
            ctx.messages.send(by, open ? "edycja.admin-end-otwarty" : "edycja.admin-end-zamkniety");
        }
    }

    // ---- zamknięty End --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (endOpen || event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL
                || event.getFrom().getWorld().getEnvironment() == World.Environment.THE_END
                || event.getPlayer().hasPermission(PERM_END_BYPASS)) {
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        long now = System.currentTimeMillis();
        if (now - lastWarn.getOrDefault(p.getUniqueId(), 0L) > 3000) {
            lastWarn.put(p.getUniqueId(), now);
            ctx.messages.send(p, "edycja.end-zamkniety", Messages.text("czas",
                    TimeFormat.duration(Duration.between(Instant.now(ctx.clock), endOpening(ctx.config, start)))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!endOpen && event.getPortalType() == PortalType.ENDER
                && event.getFrom().getWorld().getEnvironment() != World.Environment.THE_END) {
            event.setCancelled(true);
        }
    }

    // ---- nowa edycja ----------------------------------------------------------------

    void newEdition(CommandSender by) {
        if (resetting) {
            return;
        }
        resetting = true;
        long old = number;
        long next = old + 1;
        long now = ctx.clock.millis();
        String archive = "archiwum/edycja-" + old + "-" + Instant.ofEpochMilli(now).atZone(ctx.config.zone())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        ctx.plugin.getLogger().warning("NOWA EDYCJA #" + next + " (zlecił: " + by.getName() + ")");
        Bukkit.broadcast(ctx.messages.get("edycja.startuje", Messages.text("numer", next)));
        CompletableFuture<Integer> ranks = CompletableFuture.completedFuture(0);
        RankService rankService = ctx.service(RankService.class);
        if (ctx.config.bool("edycja.wygas-rangi", true) && rankService != null) {
            ranks = rankService.expireAllForNewEdition();
        }
        boolean archiveWorld = ctx.config.bool("edycja.archiwizuj-swiat", true);
        CompletableFuture<Integer> done = ranks.thenCombine(ctx.tasks.runAsync(() -> {
            recordEdition(old, start, archiveWorld ? archive : null);
            ctx.kv.put(KEY_NUMBER, String.valueOf(next));
            ctx.kv.put(KEY_START, String.valueOf(now));
            ctx.kv.put(KEY_END_OPEN, "false");
            recordEdition(next, now, null);
        }), (n, v) -> n);
        ctx.tasks.thenSync(done, expired -> {
            ctx.plugin.getLogger().info("Wygaszono rangi gameplay: " + expired + " wpisów");
            ctx.discord.announce("czat-survival", ctx.messages.raw("edycja.discord-nowa").replace("<numer>", String.valueOf(next)));
            ResetRestart.restart(ctx, new WorldReset.Plan("edycja", (int) old, archiveWorld, true,
                            ctx.config.list("edycja.dodatkowe-swiaty")),
                    ctx.messages.get("edycja.kick", Messages.text("numer", next)));
        }, e -> {
            resetting = false;
            ctx.plugin.getLogger().log(Level.SEVERE, "Nowa edycja nie powiodła się", e);
            ctx.messages.send(by, "blad-wewnetrzny");
        });
    }

    private final class EditionCommand extends BaseCommand {
        EditionCommand() {
            super(EditionModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "nowa" -> {
                    if (requirePermission(sender, PERM_ADMIN)) {
                        msg.send(sender, "edycja.potwierdz", Messages.text("numer", number + 1),
                                Messages.text("kod", codes.issue("edycja:" + sender.getName(), System.currentTimeMillis())));
                    }
                }
                case "potwierdz" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    if (args.length < 2 || !codes.consume("edycja:" + sender.getName(), args[1], System.currentTimeMillis())) {
                        msg.send(sender, "edycja.zly-kod");
                        return;
                    }
                    newEdition(sender);
                }
                case "end" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("otworz")) {
                        setEnd(true, sender);
                    } else if (args.length >= 2 && args[1].equalsIgnoreCase("zamknij")) {
                        setEnd(false, sender);
                    } else {
                        msg.send(sender, "edycja.uzycie-end");
                    }
                }
                default -> {
                    Instant now = Instant.now(ctx.clock);
                    long days = Duration.between(Instant.ofEpochMilli(start), now).toDays();
                    msg.getList("edycja.info", Messages.text("numer", number), Messages.text("dni", days + 1),
                            Messages.text("koniec", TimeFormat.duration(Duration.between(now, plannedEnd(ctx.config, start)))),
                            Messages.parsed("end", endOpen ? ctx.messages.raw("edycja.end-status-otwarty")
                                    : ctx.messages.raw("edycja.end-status-zamkniety").replace("<czas>",
                                    TimeFormat.duration(Duration.between(now, endOpening(ctx.config, start)))))).forEach(sender::sendMessage);
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (!sender.hasPermission(PERM_ADMIN)) {
                return List.of();
            }
            if (args.length == 1) {
                return List.of("nowa", "potwierdz", "end");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("end")) {
                return List.of("otworz", "zamknij");
            }
            return List.of();
        }
    }
}
