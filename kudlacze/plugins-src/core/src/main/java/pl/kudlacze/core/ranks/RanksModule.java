package pl.kudlacze.core.ranks;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.menu.Menu;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.TimeFormat;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Moduł rang: /rangi (menu Czarodzieja Kudłacza, NPC wywołuje tę komendę), /rangi kup &lt;ranga&gt;.
 * Wymaga LuckPerms.
 */
public final class RanksModule implements CoreModule {

    private final CoreContext ctx;
    private RankService service;

    public RanksModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "rangi";
    }

    @Override
    public void enable() {
        if (ctx.ranks == null) {
            throw new IllegalStateException("Moduł rang wymaga LuckPerms");
        }
        service = new RankService(loadRanks(), ctx.ranks, ctx.kc, ctx.sql, ctx.tasks, ctx.clock,
                () -> ctx.kv.getLong("survival.edycja", 1));
        ctx.provide(RankService.class, service);
        KudlaczeCore.command(ctx, "rangi", new RangiCommand());
    }

    private Map<String, RankDefinition> loadRanks() {
        File file = new File(ctx.plugin.getDataFolder(), "rangi.yml");
        YamlConfiguration yml = file.exists() ? YamlConfiguration.loadConfiguration(file)
                : YamlConfiguration.loadConfiguration(new InputStreamReader(
                ctx.plugin.getResource("rangi.yml"), StandardCharsets.UTF_8));
        return RankDefinition.load(yml.getConfigurationSection("rangi"));
    }

    /** Menu wymiany KC na rangi. */
    void openShop(Player player) {
        List<RankDefinition> list = new ArrayList<>(service.ranks().values());
        var futures = list.stream().map(r -> service.status(player.getUniqueId(), r)).toList();
        var all = java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new));
        ctx.tasks.thenSync(ctx.kc.balance(player.getUniqueId()).thenCombine(all, (bal, v) -> bal), balance -> {
            Messages m = ctx.messages;
            Menu.Builder menu = Menu.builder(m.get("rangi.menu-tytul"), 3)
                    .content(m.raw("rangi.menu-bedrock").replace("<saldo>", String.valueOf(balance)))
                    .filler(MenuService.icon(Material.PURPLE_STAINED_GLASS_PANE, Component.space(), List.of()));
            int[] slots = {10, 11, 12, 13, 14, 16, 15};
            for (int i = 0; i < list.size() && i < slots.length; i++) {
                RankDefinition r = list.get(i);
                RankService.Status st = futures.get(i).join();
                List<Component> lore = new ArrayList<>();
                lore.add(m.get("rangi.lore-koszt", Messages.text("koszt", r.cost()), Messages.text("dni", r.days())));
                if (r.requires() != null) {
                    lore.add(m.get(st.requirementMet() ? "rangi.lore-wymaga-ok" : "rangi.lore-wymaga-brak",
                            Messages.parsed("ranga", service.ranks().get(r.requires()).displayName())));
                }
                for (String perk : r.perks()) {
                    lore.add(m.parse("<gray>• " + perk));
                }
                lore.add(Component.empty());
                if (st.inherited()) {
                    lore.add(m.get("rangi.lore-masz-wyzsza"));
                } else if (st.owned()) {
                    Duration left = Duration.between(Instant.now(ctx.clock), st.expires().orElse(Instant.now(ctx.clock)));
                    lore.add(m.get("rangi.lore-aktywna", Messages.text("czas", TimeFormat.duration(left))));
                    lore.add(m.get("rangi.lore-przedluz"));
                } else {
                    lore.add(m.get("rangi.lore-kup"));
                }
                menu.button(slots[i], MenuService.icon(r.icon(), m.parse(r.displayName()), lore),
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(m.parse(r.displayName())) + " — " + r.cost() + " KC",
                        p -> confirm(p, r));
            }
            menu.button(22, MenuService.icon(Material.AMETHYST_SHARD, m.get("rangi.menu-saldo",
                            Messages.text("saldo", balance)), m.getList("rangi.menu-saldo-opis")),
                    "Saldo: " + balance + " KC", p -> p.closeInventory());
            ctx.menus.open(player, menu.build());
        }, e -> ctx.messages.send(player, "blad-wewnetrzny"));
    }

    private void confirm(Player player, RankDefinition rank) {
        Messages m = ctx.messages;
        Menu menu = Menu.builder(m.get("rangi.potwierdz-tytul", Messages.parsed("ranga", rank.displayName())), 1)
                .content(m.raw("rangi.potwierdz-bedrock").replace("<koszt>", String.valueOf(rank.cost())))
                .button(2, MenuService.icon(Material.LIME_CONCRETE, m.get("rangi.potwierdz-tak",
                        Messages.text("koszt", rank.cost())), List.of()), "Kupuję za " + rank.cost() + " KC", p -> {
                    p.closeInventory();
                    buy(p, rank.id());
                })
                .button(6, MenuService.icon(Material.RED_CONCRETE, m.get("rangi.potwierdz-nie"), List.of()),
                        "Anuluj", Player::closeInventory)
                .build();
        ctx.menus.open(player, menu);
    }

    void buy(Player player, String rankId) {
        ctx.tasks.thenSync(service.purchase(player.getUniqueId(), rankId), purchase -> {
            Messages m = ctx.messages;
            String left = TimeFormat.duration(Duration.between(Instant.now(ctx.clock), purchase.expires()));
            m.send(player, purchase.extended() ? "rangi.przedluzono" : "rangi.kupiono",
                    Messages.parsed("ranga", purchase.rank().displayName()), Messages.text("czas", left),
                    Messages.text("saldo", purchase.balanceAfter()));
            if (!purchase.extended()) {
                Bukkit.broadcast(m.get("rangi.ogloszenie", Messages.text("gracz", player.getName()),
                        Messages.parsed("ranga", purchase.rank().displayName())));
            }
        }, error -> {
            var t = pl.kudlacze.core.util.Tasks.unwrap(error);
            if (t instanceof RankService.RankException re) {
                ctx.messages.send(player, re.messageKey(), Messages.parsed("ranga", re.detail() == null ? "" : re.detail()));
            } else if (t instanceof pl.kudlacze.core.currency.KcService.InsufficientKcException ie) {
                ctx.messages.send(player, "kc.za-malo", Messages.text("saldo", ie.balance()));
            } else {
                ctx.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Błąd zakupu rangi", t);
                ctx.messages.send(player, "blad-wewnetrzny");
            }
        });
    }

    private final class RangiCommand extends BaseCommand {
        RangiCommand() {
            super(RanksModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p == null) {
                return;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("kup")) {
                if (service.find(args[1]).isEmpty()) {
                    msg.send(p, "rangi.nieznana", Messages.parsed("ranga", args[1]));
                    return;
                }
                buy(p, args[1]);
                return;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("lista")) {
                for (RankDefinition r : service.ranks().values()) {
                    msg.send(p, "rangi.lista-wpis", Messages.parsed("ranga", r.displayName()),
                            Messages.text("koszt", r.cost()), Messages.text("id", r.id()));
                }
                return;
            }
            openShop(p);
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return List.of("kup", "lista");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("kup")) {
                return List.copyOf(service.ranks().keySet());
            }
            return List.of();
        }
    }
}
