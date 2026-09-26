package pl.kudlacze.core.parkour;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.module.CoreModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Parkour w lobby: płytka startu, punkty kontrolne i meta z configu ({@code parkour.*}), trasa budowana przez
 * bootstrap.txt. Spadek na posadzkę lobby cofa do ostatniego punktu kontrolnego. Najlepsze czasy w bazie
 * ({@code parkour_czasy}), {@code /parkour [top|wyjdz]}, placeholdery {@code parkour_top_<n>_nick/czas}
 * i {@code parkour_rekord}.
 */
public final class ParkourModule implements CoreModule, Listener {

    public record Best(UUID uuid, String name, long millis) {
    }

    private static final class Run {
        final long started;
        int checkpoint = -1;

        Run(long started) {
            this.started = started;
        }
    }

    private final CoreContext ctx;
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Long> onStartPlate = new HashMap<>();
    private final Map<UUID, Long> personal = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<Best> top = List.of();
    private Location start;
    private Location finish;
    private final List<Location> checkpoints = new ArrayList<>();
    private double floorY;
    private BukkitTask refresher;
    private BukkitTask fallChecker;

    public ParkourModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "parkour";
    }

    @Override
    public void enable() {
        World w = Bukkit.getWorld(ctx.config.string("parkour.swiat", "world"));
        if (w == null) {
            throw new IllegalStateException("Brak świata parkouru");
        }
        start = point(w, ctx.config.section("parkour.start"));
        finish = point(w, ctx.config.section("parkour.meta"));
        for (Map<?, ?> m : ctx.config.raw().getMapList("parkour.punkty-kontrolne")) {
            checkpoints.add(new Location(w, num(m.get("x")), num(m.get("y")), num(m.get("z"))));
        }
        floorY = ctx.config.decimal("parkour.posadzka-y", -60);
        KudlaczeCore.command(ctx, "parkour", new ParkourCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.placeholders.registerPrefix("parkour_top_", (p, rest) -> topValue(rest));
        ctx.placeholders.register("parkour_rekord", p -> p == null || !personal.containsKey(p.getUniqueId()) ? "—"
                : format(personal.get(p.getUniqueId())));
        fallChecker = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::checkFalls, 10L, 10L);
        refresher = Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            ctx.tasks.runAsync(() -> refresh(online));
        }, 20L, 20L * 30);
    }

    @Override
    public void disable() {
        if (refresher != null) {
            refresher.cancel();
        }
        if (fallChecker != null) {
            fallChecker.cancel();
        }
        runs.clear();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    private static Location point(World w, ConfigurationSection s) {
        if (s == null) {
            throw new IllegalStateException("Brak punktu parkouru w configu");
        }
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
    }

    private static boolean same(Block b, Location l) {
        return b.getWorld().equals(l.getWorld()) && b.getX() == l.getBlockX() && b.getY() == l.getBlockY()
                && b.getZ() == l.getBlockZ();
    }

    /** Czas w formacie m:ss.SSS. */
    public static String format(long millis) {
        return String.format(java.util.Locale.ROOT, "%d:%02d.%03d", millis / 60_000, (millis / 1000) % 60, millis % 1000);
    }

    private String topValue(String rest) {
        int sep = rest.indexOf('_');
        if (sep < 0) {
            return null;
        }
        int n;
        try {
            n = Integer.parseInt(rest.substring(0, sep));
        } catch (NumberFormatException e) {
            return null;
        }
        List<Best> list = top;
        boolean present = n >= 1 && n <= list.size();
        return switch (rest.substring(sep + 1)) {
            case "nick" -> present ? list.get(n - 1).name() : "---";
            case "czas" -> present ? format(list.get(n - 1).millis()) : "-:--.---";
            default -> null;
        };
    }

    private void refresh(List<UUID> online) {
        top = ctx.sql.query("SELECT uuid, nick, najlepszy_ms FROM parkour_czasy ORDER BY najlepszy_ms ASC, ustanowiono ASC LIMIT 10",
                rs -> new Best(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
        for (UUID uuid : online) {
            best(uuid).ifPresent(ms -> personal.put(uuid, ms));
        }
    }

    Optional<Long> best(UUID uuid) {
        return ctx.sql.one("SELECT najlepszy_ms FROM parkour_czasy WHERE uuid = ?", rs -> rs.getLong(1), uuid);
    }

    /** Zapisuje czas, jeśli lepszy od dotychczasowego. Zwraca poprzedni rekord (pusty = pierwszy przejazd). */
    Optional<Long> record(UUID uuid, String name, long millis, long now) {
        return ctx.sql.transaction(c -> {
            List<Long> prev = Sql.query(c, "SELECT najlepszy_ms FROM parkour_czasy WHERE uuid = ? FOR UPDATE",
                    rs -> rs.getLong(1), uuid);
            if (prev.isEmpty()) {
                Sql.update(c, "INSERT INTO parkour_czasy (uuid, nick, najlepszy_ms, ustanowiono) VALUES (?, ?, ?, ?)",
                        uuid, name, millis, now);
                return Optional.empty();
            }
            if (millis < prev.getFirst()) {
                Sql.update(c, "UPDATE parkour_czasy SET nick = ?, najlepszy_ms = ?, ustanowiono = ? WHERE uuid = ?",
                        name, millis, now, uuid);
            }
            return Optional.of(prev.getFirst());
        });
    }

    @EventHandler
    public void onPlate(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) {
            return;
        }
        Player p = event.getPlayer();
        Block b = event.getClickedBlock();
        long now = System.currentTimeMillis();
        if (same(b, start)) {
            // płytka zgłasza się co kilka ticków, dopóki gracz na niej stoi — licznik startuje przy zejściu,
            // a komunikat pojawia się tylko przy wejściu na płytkę
            Long last = onStartPlate.put(p.getUniqueId(), now);
            runs.put(p.getUniqueId(), new Run(now));
            if (last == null || now - last > 1500) {
                ctx.messages.send(p, "parkour.start");
            }
            return;
        }
        Run run = runs.get(p.getUniqueId());
        if (run == null) {
            return;
        }
        for (int i = 0; i < checkpoints.size(); i++) {
            if (same(b, checkpoints.get(i)) && run.checkpoint < i) {
                run.checkpoint = i;
                ctx.messages.send(p, "parkour.punkt-kontrolny", Messages.text("numer", i + 1),
                        Messages.text("czas", format(now - run.started)));
                return;
            }
        }
        if (same(b, finish)) {
            runs.remove(p.getUniqueId());
            long time = now - run.started;
            UUID uuid = p.getUniqueId();
            String name = p.getName();
            ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> record(uuid, name, time, now)), prev -> {
                if (prev.isEmpty() || time < prev.get()) {
                    personal.put(uuid, time);
                }
                ctx.messages.send(p, prev.isEmpty() ? "parkour.meta-pierwszy" : time < prev.get() ? "parkour.meta-rekord"
                        : "parkour.meta", Messages.text("czas", format(time)),
                        Messages.text("rekord", format(prev.map(v -> Math.min(v, time)).orElse(time))));
                List<Best> current = top;
                if (current.isEmpty() || time < current.getFirst().millis()) {
                    Bukkit.broadcast(ctx.messages.get("parkour.rekord-serwera", Messages.text("gracz", name),
                            Messages.text("czas", format(time))));
                }
                List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
                ctx.tasks.runAsync(() -> refresh(online));
            }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            checkFall(event.getPlayer(), event.getTo());
        }
    }

    /** Co pół sekundy — spadek wykryty także wtedy, gdy ruch nie wygenerował zdarzenia (teleport, lag). */
    private void checkFalls() {
        for (UUID uuid : List.copyOf(runs.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                checkFall(p, p.getLocation());
            }
        }
    }

    private void checkFall(Player p, Location at) {
        Run run = runs.get(p.getUniqueId());
        if (run == null || !at.getWorld().equals(start.getWorld()) || at.getY() > floorY + 0.01
                || at.distanceSquared(start) < 16) {
            return;
        }
        // spadł na posadzkę lobby — powrót do ostatniego punktu kontrolnego (czas biegnie dalej)
        Location back = (run.checkpoint >= 0 ? checkpoints.get(run.checkpoint) : start).clone().add(0.5, 0, 0.5);
        back.setYaw(at.getYaw());
        back.setPitch(at.getPitch());
        p.teleportAsync(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        ctx.messages.send(p, "parkour.spadek");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        onStartPlate.remove(event.getPlayer().getUniqueId());
        personal.remove(event.getPlayer().getUniqueId());
    }

    private final class ParkourCommand extends BaseCommand {
        ParkourCommand() {
            super(ParkourModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("top")) {
                List<Best> list = top;
                msg.send(sender, "parkour.top-naglowek");
                if (list.isEmpty()) {
                    msg.send(sender, "parkour.top-pusty");
                }
                for (int i = 0; i < list.size(); i++) {
                    msg.send(sender, "parkour.top-wpis", Messages.text("miejsce", i + 1),
                            Messages.text("gracz", list.get(i).name()), Messages.text("czas", format(list.get(i).millis())));
                }
                return;
            }
            Player p = requirePlayer(sender);
            if (p == null) {
                return;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("wyjdz")) {
                runs.remove(p.getUniqueId());
                msg.send(p, "parkour.wyjscie");
                return;
            }
            runs.remove(p.getUniqueId());
            Location l = start.clone().add(0.5, 0, -1.5);
            p.teleportAsync(l, PlayerTeleportEvent.TeleportCause.COMMAND);
            msg.send(p, "parkour.teleport");
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? List.of("top", "wyjdz") : List.of();
        }
    }
}
