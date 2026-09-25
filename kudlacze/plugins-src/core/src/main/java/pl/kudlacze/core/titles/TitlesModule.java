package pl.kudlacze.core.titles;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import pl.kudlacze.core.menu.Menu;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.module.CoreModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /tytul — wybór aktywnego tytułu kosmetycznego. Placeholdery {@code %kudlacze_tytul%} (kody §),
 * {@code tytul_tab} (przyrostek TAB-a) i {@code tytul_czat} (MiniMessage, format czatu). Nazwy tytułów: {@code tytuly.nazwy.<id>} w messages_pl.yml.
 */
public final class TitlesModule implements CoreModule, Listener {

    public static final String PERM_ADMIN = "kudlacze.tytul.admin";
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final CoreContext ctx;
    private final Map<UUID, String> active = new ConcurrentHashMap<>();
    private TitleService titles;
    private BukkitTask refresher;

    public TitlesModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "tytuly";
    }

    @Override
    public void enable() {
        titles = ctx.service(TitleService.class);
        KudlaczeCore.command(ctx, "tytul", new TitleCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.placeholders.register("tytul_czat", p -> p == null ? "" : display(active.get(p.getUniqueId())));
        ctx.placeholders.register("tytul", p -> {
            String mm = p == null ? "" : display(active.get(p.getUniqueId()));
            return mm.isEmpty() ? "" : SECTION.serialize(ctx.messages.parse(mm));
        });
        // przyrostek TAB-a (groups.yml: tabsuffix) — odstęp przed tytułem
        ctx.placeholders.register("tytul_tab", p -> {
            String mm = p == null ? "" : display(active.get(p.getUniqueId())).trim();
            return mm.isEmpty() ? "" : " " + SECTION.serialize(ctx.messages.parse(mm));
        });
        Bukkit.getOnlinePlayers().forEach(p -> load(p.getUniqueId()));
        // tytuły nadane na innym serwerze (smok, sezon) pojawiają się bez ponownego wejścia
        refresher = Bukkit.getScheduler().runTaskTimer(ctx.plugin,
                () -> Bukkit.getOnlinePlayers().forEach(p -> load(p.getUniqueId())), 20L * 60, 20L * 60);
    }

    @Override
    public void disable() {
        if (refresher != null) {
            refresher.cancel();
        }
        active.clear();
    }

    /** MiniMessage tytułu z odstępem na końcu albo pusty tekst. */
    public String display(String titleId) {
        if (titleId == null) {
            return "";
        }
        String mm = ctx.messages.rawOr("tytuly.nazwy." + titleId, null);
        return mm == null ? "<gray>[" + titleId + "]</gray> " : mm + " ";
    }

    private void load(UUID uuid) {
        ctx.tasks.supplyAsync(() -> titles.active(uuid)).thenAccept(t -> {
            if (t.isPresent()) {
                active.put(uuid, t.get());
            } else {
                active.remove(uuid);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }

    private void openMenu(Player player) {
        UUID uuid = player.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> titles.owned(uuid)), owned -> {
            Messages m = ctx.messages;
            if (owned.isEmpty()) {
                m.send(player, "tytuly.brak");
                return;
            }
            int rows = Math.min(6, Math.max(2, (owned.size() + 1 + 8) / 9 + 1));
            Menu.Builder menu = Menu.builder(m.get("tytuly.menu-tytul"), rows).content(m.raw("tytuly.menu-bedrock"));
            String current = active.get(uuid);
            int slot = 0;
            for (TitleService.Owned t : owned) {
                if (slot >= rows * 9 - 1) {
                    break;
                }
                Component name = m.parse(display(t.id()).trim());
                List<Component> lore = new ArrayList<>();
                lore.add(m.get("tytuly.lore-zrodlo", Messages.text("zrodlo", t.source())));
                lore.add(m.get(t.id().equals(current) ? "tytuly.lore-aktywny" : "tytuly.lore-ustaw"));
                menu.button(slot++, MenuService.icon(t.id().equals(current) ? Material.GLOW_ITEM_FRAME : Material.NAME_TAG,
                                name, lore), PlainTextComponentSerializer.plainText().serialize(name),
                        p -> { p.closeInventory(); setActive(p, t.id()); });
            }
            menu.button(rows * 9 - 1, MenuService.icon(Material.BARRIER, m.get("tytuly.zdejmij-przycisk"), List.of()),
                    "Bez tytułu", p -> { p.closeInventory(); clear(p); });
            ctx.menus.open(player, menu.build());
        }, e -> ctx.messages.send(player, "blad-wewnetrzny"));
    }

    private void setActive(Player p, String titleId) {
        UUID uuid = p.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> titles.setActive(uuid, titleId)), ok -> {
            if (ok) {
                active.put(uuid, titleId);
                ctx.messages.send(p, "tytuly.ustawiono", Messages.parsed("tytul", display(titleId).trim()));
            } else {
                ctx.messages.send(p, "tytuly.nie-masz");
            }
        }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
    }

    private void clear(Player p) {
        UUID uuid = p.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.runAsync(() -> titles.clearActive(uuid)), v -> {
            active.remove(uuid);
            ctx.messages.send(p, "tytuly.zdjeto");
        }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
    }

    private final class TitleCommand extends BaseCommand {
        TitleCommand() {
            super(TitlesModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            if (args.length >= 3 && (args[0].equalsIgnoreCase("nadaj") || args[0].equalsIgnoreCase("odbierz"))) {
                if (!requirePermission(sender, PERM_ADMIN)) {
                    return;
                }
                boolean grant = args[0].equalsIgnoreCase("nadaj");
                String titleId = args[2].toLowerCase(java.util.Locale.ROOT);
                ctx.tasks.thenSync(findPlayer(args[1]), found -> {
                    if (found.isEmpty()) {
                        msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", args[1]));
                        return;
                    }
                    UUID uuid = found.get().uuid();
                    ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
                        if (grant) {
                            titles.grant(uuid, titleId, "administracja", ctx.clock.millis());
                            return true;
                        }
                        return titles.revoke(uuid, titleId);
                    }), ok -> {
                        load(uuid);
                        msg.send(sender, grant ? "tytuly.admin-nadano" : "tytuly.admin-odebrano",
                                Messages.text("gracz", found.get().name()), Messages.parsed("tytul", display(titleId).trim()));
                    }, e -> handleError(sender, e));
                }, e -> handleError(sender, e));
                return;
            }
            Player p = requirePlayer(sender);
            if (p == null) {
                return;
            }
            if (args.length >= 1 && (args[0].equalsIgnoreCase("zdejmij") || args[0].equalsIgnoreCase("brak"))) {
                clear(p);
            } else if (args.length >= 2 && args[0].equalsIgnoreCase("ustaw")) {
                setActive(p, args[1].toLowerCase(java.util.Locale.ROOT));
            } else {
                openMenu(p);
            }
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return sender.hasPermission(PERM_ADMIN) ? List.of("ustaw", "zdejmij", "nadaj", "odbierz")
                        : List.of("ustaw", "zdejmij");
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("nadaj") || args[0].equalsIgnoreCase("odbierz"))) {
                return onlineNames();
            }
            return List.of();
        }
    }
}
