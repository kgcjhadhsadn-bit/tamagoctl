package pl.kudlacze.core.ranking;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.seasons.SeasonSchedule;
import pl.kudlacze.core.seasons.SeasonService;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ranking sezonu na każdym serwerze (wspólna baza): {@code /ranking [archiwum <sezon>]} i placeholdery
 * PlaceholderAPI dla hologramu w lobby i TAB-a:
 * {@code %kudlacze_top_<n>_nick%}, {@code top_<n>_punkty}, {@code sezon_numer}, {@code sezon_koniec},
 * {@code smok_za}, {@code sezon_punkty}, {@code sezon_miejsce}, {@code krysztaly}.
 */
public final class RankingModule implements CoreModule, Listener {

    private record Mine(long points, long crystals, String place) {
    }

    private final CoreContext ctx;
    private final Map<UUID, Mine> mine = new ConcurrentHashMap<>();
    private volatile List<SeasonService.Entry> top = List.of();
    private volatile int season = 1;
    private SeasonService seasons;
    private BukkitTask refresher;

    public RankingModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "ranking";
    }

    @Override
    public void enable() {
        seasons = ctx.service(SeasonService.class);
        KudlaczeCore.command(ctx, "ranking", new RankingCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.placeholders.registerPrefix("top_", (p, rest) -> topValue(rest));
        ctx.placeholders.register("sezon_numer", p -> String.valueOf(season));
        ctx.placeholders.register("sezon_koniec", p -> TimeFormat.duration(
                Duration.between(Instant.now(ctx.clock), SeasonSchedule.nextReset(ctx.config, Instant.now(ctx.clock)))));
        ctx.placeholders.register("smok_za", p -> TimeFormat.duration(
                Duration.between(Instant.now(ctx.clock), SeasonSchedule.nextDragon(ctx.config, Instant.now(ctx.clock)))));
        ctx.placeholders.register("sezon_punkty", p -> p == null ? "0" : String.valueOf(mineOf(p).points()));
        ctx.placeholders.register("krysztaly", p -> p == null ? "0" : String.valueOf(mineOf(p).crystals()));
        ctx.placeholders.register("sezon_miejsce", p -> p == null ? "—" : mineOf(p).place());
        long period = ctx.config.integer("ranking.odswiezanie-sekund", 30) * 20L;
        // lista graczy zbierana na wątku głównym, zapytania do bazy w tle
        refresher = Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            ctx.tasks.runAsync(() -> refresh(online));
        }, 20L, period);
    }

    @Override
    public void disable() {
        if (refresher != null) {
            refresher.cancel();
        }
    }

    private Mine mineOf(OfflinePlayer p) {
        return mine.getOrDefault(p.getUniqueId(), new Mine(0, 0, "—"));
    }

    /** {@code rest} = „3_nick” albo „3_punkty”. */
    String topValue(String rest) {
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
        List<SeasonService.Entry> list = top;
        boolean present = n >= 1 && n <= list.size();
        return switch (rest.substring(sep + 1)) {
            case "nick" -> present ? list.get(n - 1).name() : ctx.messages.rawOr("ranking.puste-miejsce", "---");
            case "punkty" -> present ? String.valueOf(list.get(n - 1).points()) : "0";
            default -> null;
        };
    }

    private void refresh(List<UUID> online) {
        try {
            season = seasons.currentSeason();
            top = seasons.top(ctx.config.integer("ranking.rozmiar", 10));
            online.forEach(this::loadMine);
        } catch (RuntimeException e) {
            ctx.plugin.getLogger().warning("Nie odświeżono rankingu: " + e.getMessage());
        }
    }

    private void loadMine(UUID uuid) {
        var entry = seasons.entry(uuid);
        String place = seasons.place(uuid).map(String::valueOf).orElse("—");
        mine.put(uuid, new Mine(entry.map(SeasonService.Entry::points).orElse(0L),
                entry.map(SeasonService.Entry::crystals).orElse(0L), place));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ctx.tasks.runAsync(() -> loadMine(uuid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mine.remove(event.getPlayer().getUniqueId());
    }

    private final class RankingCommand extends BaseCommand {
        RankingCommand() {
            super(RankingModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("archiwum")) {
                archive(sender, args);
                return;
            }
            UUID self = sender instanceof Player p ? p.getUniqueId() : null;
            ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new Object[]{seasons.currentSeason(),
                    seasons.top(ctx.config.integer("ranking.rozmiar", 10)),
                    self == null ? java.util.Optional.empty() : seasons.entry(self),
                    self == null ? java.util.Optional.empty() : seasons.place(self)}), d -> {
                @SuppressWarnings("unchecked")
                List<SeasonService.Entry> list = (List<SeasonService.Entry>) d[1];
                @SuppressWarnings("unchecked")
                var entry = (java.util.Optional<SeasonService.Entry>) d[2];
                @SuppressWarnings("unchecked")
                var place = (java.util.Optional<Integer>) d[3];
                msg.send(sender, "ranking.naglowek", Messages.text("sezon", d[0]), Messages.text("czas",
                        TimeFormat.duration(Duration.between(Instant.now(ctx.clock),
                                SeasonSchedule.nextReset(ctx.config, Instant.now(ctx.clock))))));
                if (list.isEmpty()) {
                    msg.send(sender, "ranking.pusty");
                }
                for (int i = 0; i < list.size(); i++) {
                    SeasonService.Entry e = list.get(i);
                    msg.send(sender, i < 3 ? "ranking.wpis-podium" : "ranking.wpis", Messages.text("miejsce", i + 1),
                            Messages.text("gracz", e.name()), Messages.text("punkty", e.points()),
                            Messages.text("krysztaly", e.crystals()));
                }
                if (self != null) {
                    msg.send(sender, "ranking.twoje", Messages.text("punkty", entry.map(SeasonService.Entry::points).orElse(0L)),
                            Messages.text("miejsce", place.map(String::valueOf).orElse("—")),
                            Messages.text("krysztaly", entry.map(SeasonService.Entry::crystals).orElse(0L)));
                }
            }, e -> handleError(sender, e));
        }

        private void archive(CommandSender sender, String[] args) {
            ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
                int wanted = args.length >= 2 ? Integer.parseInt(args[1]) : seasons.currentSeason() - 1;
                return Map.entry(wanted, seasons.archive(wanted));
            }), r -> {
                if (r.getValue().isEmpty()) {
                    msg.send(sender, "ranking.archiwum-brak", Messages.text("sezon", r.getKey()));
                    return;
                }
                msg.send(sender, "ranking.archiwum-naglowek", Messages.text("sezon", r.getKey()));
                for (SeasonService.Archived a : r.getValue()) {
                    msg.send(sender, a.place() <= 3 ? "ranking.wpis-podium" : "ranking.wpis",
                            Messages.text("miejsce", a.place()), Messages.text("gracz", a.name()),
                            Messages.text("punkty", a.points()), Messages.text("krysztaly", "-"));
                }
            }, e -> {
                if (pl.kudlacze.core.util.Tasks.unwrap(e) instanceof NumberFormatException) {
                    msg.send(sender, "zla-liczba", Messages.text("wartosc", args[1]));
                } else {
                    handleError(sender, e);
                }
            });
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? List.of("archiwum") : List.of();
        }
    }
}
