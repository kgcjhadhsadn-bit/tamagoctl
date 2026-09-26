package pl.kudlacze.core;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import pl.kudlacze.core.config.CoreConfig;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.CurrencyModule;
import pl.kudlacze.core.currency.KcItems;
import pl.kudlacze.core.db.Database;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.hooks.FloodgateBedrock;
import pl.kudlacze.core.hooks.LuckPermsBridge;
import pl.kudlacze.core.hooks.PapiExpansion;
import pl.kudlacze.core.hooks.VaultMoney;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.Tasks;

import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Plugin core sieci Kudłacze. Jeden jar na wszystkie serwery — zestaw modułów wybiera
 * sekcja {@code moduly} w config.yml danego serwera.
 */
public class KudlaczeCore extends JavaPlugin implements Listener {

    /** Tryb testów (MockBukkit): baza H2 z właściwości {@code kudlacze.jdbc}, zadania synchroniczne. */
    public static final String TEST_JDBC = "kudlacze.jdbc";

    private final List<CoreModule> modules = new ArrayList<>();
    private final List<CoreModule> enabled = new ArrayList<>();
    private ExecutorService dbExecutor;
    private Database database;
    private CoreContext ctx;
    private Clock clock = Clock.systemUTC();

    @Override
    public void onLoad() {
        saveDefaultConfig();
        // messages_pl.yml, rangi.yml i questy.yml NIE są kopiowane do folderu pluginu: źródłem jest jar
        // (aktualizacje tekstów i balansu wchodzą z nową wersją), a plik w folderze to opcjonalne nadpisanie.
        // Czyszczenie światów (nowa edycja / nowy sezon) musi się odbyć przed ich załadowaniem.
        try {
            pl.kudlacze.core.world.WorldReset.runPending(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Nie udało się wykonać zaplanowanego resetu świata", e);
        }
    }

    @Override
    public void onEnable() {
        CoreConfig config = new CoreConfig(getConfig());
        Messages messages;
        try (InputStream def = getResource("messages_pl.yml")) {
            messages = Messages.load(getDataFolder(), def, getLogger());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        String testJdbc = System.getProperty(TEST_JDBC);
        Tasks tasks;
        if (testJdbc != null) {
            tasks = Tasks.direct(getLogger());
            database = Database.jdbc(testJdbc, getLogger());
        } else {
            AtomicInteger n = new AtomicInteger();
            dbExecutor = Executors.newFixedThreadPool(config.integer("baza.watki", 4), r -> {
                Thread t = new Thread(r, "kudlacze-db-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            tasks = new Tasks(dbExecutor, r -> {
                if (Bukkit.isPrimaryThread()) {
                    r.run();
                } else {
                    Bukkit.getScheduler().runTask(this, r);
                }
            }, getLogger());
            database = Database.mariadb(config.string("baza.host", "mariadb"), config.integer("baza.port", 3306),
                    config.string("baza.nazwa", "kudlacze"), config.string("baza.uzytkownik", "kudlacze"),
                    config.string("baza.haslo", ""), config.integer("baza.pula", 6), getLogger());
        }
        try {
            database.migrate();
        } catch (IllegalStateException e) {
            getLogger().log(Level.SEVERE, e.getMessage() + " — plugin core zostaje wyłączony.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        KcItems kcItems = new KcItems("kudlacze", config.string("waluta.sekret-przedmiotow", ""),
                messages.raw("kc.przedmiot-nazwa"), messages.rawList("kc.przedmiot-opis"));
        ctx = new CoreContext(this, config, messages, tasks, clock, new Sql(database), kcItems);
        detectHooks(ctx);
        ctx.menus = new MenuService(ctx.bedrock, tasks);
        ctx.provide(pl.kudlacze.core.seasons.SeasonService.class, new pl.kudlacze.core.seasons.SeasonService(ctx.sql, ctx.kv));
        ctx.provide(pl.kudlacze.core.titles.TitleService.class, new pl.kudlacze.core.titles.TitleService(ctx.sql));
        Bukkit.getPluginManager().registerEvents(ctx.menus, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        registerModules(ctx);
        for (CoreModule m : modules) {
            if (!config.moduleEnabled(m.id())) {
                continue;
            }
            try {
                m.enable();
                enabled.add(m);
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Nie udało się włączyć modułu " + m.id(), e);
            }
        }
        getLogger().info("Kudłacze core — serwer '" + config.server() + "', moduły: "
                + enabled.stream().map(CoreModule::id).toList());

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PapiExpansion(ctx.placeholders, getPluginMeta().getVersion()).register();
        }
    }

    private void detectHooks(CoreContext c) {
        var pm = Bukkit.getPluginManager();
        if (pm.isPluginEnabled("LuckPerms")) {
            LuckPermsBridge lp = new LuckPermsBridge();
            c.meta = lp;
            c.ranks = lp;
        }
        if (pm.isPluginEnabled("floodgate")) {
            c.bedrock = new FloodgateBedrock();
        }
        if (pm.isPluginEnabled("Vault")) {
            var money = VaultMoney.create();
            if (money != null) {
                c.money = money;
            }
        }
    }

    /** Wszystkie moduły w kolejności włączania. */
    private void registerModules(CoreContext c) {
        modules.add(new CurrencyModule(c));
        modules.add(new pl.kudlacze.core.ranks.RanksModule(c));
        modules.add(new pl.kudlacze.core.death.DeathModule(c));
        modules.add(new pl.kudlacze.core.perks.PerksModule(c));
        modules.add(new pl.kudlacze.core.chat.ChatModule(c));
        modules.add(new pl.kudlacze.core.lumberjack.LumberjackModule(c));
        modules.add(new pl.kudlacze.core.vip.VipModule(c));
        modules.add(new pl.kudlacze.core.lobby.LobbyModule(c));
        modules.add(new pl.kudlacze.core.bootstrap.BootstrapModule(c));
        modules.add(new pl.kudlacze.core.network.NetworkModule(c));
        modules.add(new pl.kudlacze.core.info.InfoModule(c));
        modules.add(new pl.kudlacze.core.titles.TitlesModule(c));
        modules.add(new pl.kudlacze.core.ranking.RankingModule(c));
        modules.add(new pl.kudlacze.core.seasons.SeasonsModule(c));
        modules.add(new pl.kudlacze.core.dragon.DragonModule(c));
        modules.add(new pl.kudlacze.core.edition.EditionModule(c));
        modules.add(new pl.kudlacze.core.afk.AfkModule(c));
        modules.add(new pl.kudlacze.core.contest.ContestModule(c));
        modules.add(new pl.kudlacze.core.parkour.ParkourModule(c));
        modules.add(new pl.kudlacze.core.shop.ShopModule(c));
        modules.add(new pl.kudlacze.core.punish.PunishmentLogModule(c));
        modules.add(new pl.kudlacze.core.restart.RestartModule(c));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var p = event.getPlayer();
        ctx.tasks.runAsync(() -> ctx.players.recordJoin(p.getUniqueId(), p.getName(), ctx.clock.millis()));
    }

    @Override
    public void onDisable() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            try {
                enabled.get(i).disable();
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Błąd przy wyłączaniu modułu " + enabled.get(i).id(), e);
            }
        }
        enabled.clear();
        modules.clear();
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                dbExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
    }

    /** Rejestracja komendy z plugin.yml. */
    public static void command(CoreContext ctx, String name, TabExecutor executor) {
        PluginCommand cmd = ctx.plugin.getCommand(name);
        if (cmd == null) {
            ctx.plugin.getLogger().warning("Brak komendy /" + name + " w plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    public CoreContext context() {
        return ctx;
    }

    public List<String> enabledModules() {
        return enabled.stream().map(CoreModule::id).toList();
    }

    /** Tylko testy: stały zegar. Wywołać przed włączeniem pluginu. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
