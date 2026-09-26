package pl.kudlacze.core.contest;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.edition.EditionModule;
import pl.kudlacze.core.hooks.WorldGuardBridge;
import pl.kudlacze.core.menu.Menu;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.titles.TitleService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Konkurs budowlany „Kudłate Chaty” (survival). Zgłoszenia ({@code /chaty zgloszenie [opis]}) przyjmowane są
 * w ostatnich {@code edycja.konkurs-dni} dniach edycji, jedno na gracza, z miejsca na własnej działce.
 * Personel ({@code kudlacze.chaty.admin}): lista, teleport, ocena 1–10, ogłoszenie wyników — top 3 dostaje
 * 60/40/20 KC i tytuł. Okno można wymusić: {@code /chaty okno otworz|zamknij|auto}.
 */
public final class ContestModule implements CoreModule {

    public static final String PERM_ADMIN = "kudlacze.chaty.admin";
    static final String KEY_WINDOW = "konkurs.okno";

    private final CoreContext ctx;
    private ContestService contest;
    private WorldGuardBridge worldGuard;

    public ContestModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "konkurs";
    }

    @Override
    public void enable() {
        contest = new ContestService(ctx.sql);
        ctx.provide(ContestService.class, contest);
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            worldGuard = new WorldGuardBridge();
        }
        KudlaczeCore.command(ctx, "chaty", new ContestCommand());
    }

    private int edition() {
        return (int) ctx.kv.getLong(EditionModule.KEY_NUMBER, 1);
    }

    /** Wywoływać w wątku bazy. */
    private boolean windowOpen() {
        String mode = ctx.kv.get(KEY_WINDOW).orElse("auto");
        return switch (mode) {
            case "otwarte" -> true;
            case "zamkniete" -> false;
            default -> EditionModule.contestOpen(ctx.config, ctx.kv.getLong(EditionModule.KEY_START, ctx.clock.millis()),
                    Instant.now(ctx.clock));
        };
    }

    private void openMenu(Player p) {
        UUID uuid = p.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new Object[]{windowOpen(), contest.entry(edition(), uuid)}), d -> {
            boolean open = (Boolean) d[0];
            @SuppressWarnings("unchecked")
            var mine = (java.util.Optional<ContestService.Entry>) d[1];
            Messages m = ctx.messages;
            Menu.Builder menu = Menu.builder(m.get("konkurs.menu-tytul"), 3)
                    .content(m.raw(open ? "konkurs.menu-bedrock-otwarte" : "konkurs.menu-bedrock-zamkniete"))
                    .filler(MenuService.icon(Material.BROWN_STAINED_GLASS_PANE, Component.space(), List.of()));
            menu.button(11, MenuService.icon(Material.OAK_SIGN, m.get("konkurs.menu-zasady"), m.getList("konkurs.zasady")),
                    "Zasady konkursu", pl -> m.getList("konkurs.zasady").forEach(pl::sendMessage));
            if (mine.isPresent()) {
                ContestService.Entry e = mine.get();
                menu.button(15, MenuService.icon(Material.OAK_DOOR, m.get("konkurs.menu-moje"),
                                List.of(m.get("konkurs.lore-miejsce", Messages.text("x", (int) e.x()), Messages.text("y", (int) e.y()),
                                        Messages.text("z", (int) e.z())), m.get(e.rating() == null ? "konkurs.lore-bez-oceny"
                                        : "konkurs.lore-ocena", Messages.text("ocena", e.rating() == null ? 0 : e.rating())))),
                        "Moje zgłoszenie", pl -> teleport(pl, e));
            } else if (open) {
                menu.button(15, MenuService.icon(Material.EMERALD, m.get("konkurs.menu-zglos"), m.getList("konkurs.menu-zglos-opis")),
                        "Zgłoś budowlę (stoisz w niej)", pl -> {
                            pl.closeInventory();
                            submit(pl, "");
                        });
            } else {
                menu.button(15, MenuService.icon(Material.CLOCK, m.get("konkurs.menu-zamkniete"), List.of()),
                        "Zgłoszenia zamknięte", Player::closeInventory);
            }
            ctx.menus.open(p, menu.build());
        }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
    }

    void submit(Player p, String description) {
        if (worldGuard != null && ctx.config.bool("konkurs.wymagaj-dzialki", true) && !worldGuard.ownsPlotAt(p, p.getLocation())) {
            ctx.messages.send(p, "konkurs.nie-twoja-dzialka");
            return;
        }
        Location l = p.getLocation();
        UUID uuid = p.getUniqueId();
        String name = p.getName();
        String desc = description.length() > 200 ? description.substring(0, 200) : description;
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
            if (!windowOpen()) {
                return "konkurs.zamkniete";
            }
            return contest.submit(edition(), uuid, name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), desc,
                    ctx.clock.millis()) ? "konkurs.zgloszono" : "konkurs.juz-zgloszono";
        }), key -> {
            ctx.messages.send(p, key);
            if (key.equals("konkurs.zgloszono")) {
                Bukkit.broadcast(ctx.messages.get("konkurs.ogloszenie-zgloszenia", Messages.text("gracz", name)));
            }
        }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
    }

    private void teleport(Player p, ContestService.Entry e) {
        World w = Bukkit.getWorld(e.world());
        if (w == null) {
            ctx.messages.send(p, "konkurs.brak-swiata");
            return;
        }
        p.closeInventory();
        p.teleportAsync(new Location(w, e.x(), e.y(), e.z(), p.getLocation().getYaw(), p.getLocation().getPitch()),
                PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    /** Ogłoszenie wyników i nagrody (raz na edycję). */
    private void results(CommandSender sender) {
        List<Long> kc = ctx.config.raw().getLongList("konkurs.nagrody-kc");
        List<String> titleIds = ctx.config.list("konkurs.tytuly");
        TitleService titles = ctx.service(TitleService.class);
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
            int edition = edition();
            String flag = "konkurs." + edition + ".rozdano";
            List<ContestService.Entry> winners = contest.winners(edition, Math.max(kc.size(), titleIds.size()));
            if (ctx.kv.get(flag).isPresent() || winners.isEmpty()) {
                return new Object[]{edition, winners, false};
            }
            ctx.kv.put(flag, String.valueOf(ctx.clock.millis()));
            long now = ctx.clock.millis();
            for (int i = 0; i < winners.size(); i++) {
                ContestService.Entry w = winners.get(i);
                if (i < kc.size() && kc.get(i) > 0) {
                    ctx.kc.give(w.uuid(), kc.get(i), KcService.TxType.KONKURS, "Kudłate Chaty ed. " + edition + " miejsce " + (i + 1)).join();
                }
                if (i < titleIds.size() && titles != null) {
                    titles.grant(w.uuid(), titleIds.get(i), "Kudłate Chaty (edycja " + edition + ")", now);
                }
            }
            return new Object[]{edition, winners, true};
        }), d -> {
            @SuppressWarnings("unchecked")
            List<ContestService.Entry> winners = (List<ContestService.Entry>) d[1];
            if (!(Boolean) d[2]) {
                ctx.messages.send(sender, winners.isEmpty() ? "konkurs.brak-ocen" : "konkurs.juz-rozdano");
                return;
            }
            Bukkit.broadcast(ctx.messages.get("konkurs.wyniki-naglowek", Messages.text("edycja", d[0])));
            StringBuilder discord = new StringBuilder(ctx.messages.raw("konkurs.discord-wyniki").replace("<edycja>", String.valueOf(d[0])));
            for (int i = 0; i < winners.size(); i++) {
                ContestService.Entry w = winners.get(i);
                long prize = i < kc.size() ? kc.get(i) : 0;
                Bukkit.broadcast(ctx.messages.get("konkurs.wyniki-wpis", Messages.text("miejsce", i + 1),
                        Messages.text("gracz", w.name()), Messages.text("ocena", w.rating()), Messages.text("kc", prize)));
                discord.append("\n").append(i + 1).append(". ").append(w.name()).append(" — ocena ").append(w.rating())
                        .append("/10, +").append(prize).append(" KC");
            }
            ctx.discord.announce("czat-survival", discord.toString());
        }, e -> ctx.messages.send(sender, "blad-wewnetrzny"));
    }

    private final class ContestCommand extends BaseCommand {
        ContestCommand() {
            super(ContestModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "zgloszenie", "zglos" -> {
                    Player p = requirePlayer(sender);
                    if (p != null) {
                        submit(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    }
                }
                case "wycofaj" -> {
                    Player p = requirePlayer(sender);
                    if (p == null) {
                        return;
                    }
                    UUID uuid = p.getUniqueId();
                    ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> windowOpen() && contest.withdraw(edition(), uuid)),
                            ok -> msg.send(p, ok ? "konkurs.wycofano" : "konkurs.nie-wycofano"), e -> handleError(p, e));
                }
                case "lista" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> contest.list(edition())), list -> {
                        msg.send(sender, "konkurs.lista-naglowek", Messages.text("ilosc", list.size()));
                        for (ContestService.Entry e : list) {
                            msg.send(sender, "konkurs.lista-wpis", Messages.text("gracz", e.name()),
                                    Messages.text("ocena", e.rating() == null ? "—" : e.rating() + "/10"),
                                    Messages.text("opis", e.description() == null ? "" : e.description()));
                        }
                    }, e -> handleError(sender, e));
                }
                case "tp" -> {
                    Player p = requirePlayer(sender);
                    if (p == null || !requirePermission(p, PERM_ADMIN) || args.length < 2) {
                        return;
                    }
                    ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> contest.byName(edition(), args[1])), e -> {
                        if (e.isEmpty()) {
                            msg.send(p, "konkurs.brak-zgloszenia", Messages.text("gracz", args[1]));
                        } else {
                            teleport(p, e.get());
                        }
                    }, e -> handleError(p, e));
                }
                case "ocen" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    if (args.length < 3) {
                        msg.send(sender, "konkurs.uzycie-ocen");
                        return;
                    }
                    int rating;
                    try {
                        rating = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        msg.send(sender, "zla-liczba", Messages.text("wartosc", args[2]));
                        return;
                    }
                    UUID rater = sender instanceof Player p ? p.getUniqueId() : null;
                    ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
                        var e = contest.byName(edition(), args[1]);
                        return e.isPresent() && contest.rate(edition(), e.get().uuid(), rating, rater);
                    }), ok -> msg.send(sender, ok ? "konkurs.oceniono" : "konkurs.brak-zgloszenia",
                            Messages.text("gracz", args[1]), Messages.text("ocena", rating)), e -> handleError(sender, e));
                }
                case "wyniki" -> {
                    if (requirePermission(sender, PERM_ADMIN)) {
                        results(sender);
                    }
                }
                case "okno" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    String mode = args.length >= 2 ? switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "otworz" -> "otwarte";
                        case "zamknij" -> "zamkniete";
                        default -> "auto";
                    } : "auto";
                    ctx.tasks.thenSync(ctx.tasks.runAsync(() -> ctx.kv.put(KEY_WINDOW, mode)),
                            v -> msg.send(sender, "konkurs.okno", Messages.text("tryb", mode)), e -> handleError(sender, e));
                }
                default -> {
                    Player p = requirePlayer(sender);
                    if (p != null) {
                        openMenu(p);
                    }
                }
            }
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return sender.hasPermission(PERM_ADMIN)
                        ? List.of("zgloszenie", "wycofaj", "lista", "tp", "ocen", "wyniki", "okno")
                        : List.of("zgloszenie", "wycofaj");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("okno")) {
                return List.of("otworz", "zamknij", "auto");
            }
            return List.of();
        }
    }
}
