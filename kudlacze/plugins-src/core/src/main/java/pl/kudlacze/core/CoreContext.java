package pl.kudlacze.core;

import org.bukkit.plugin.java.JavaPlugin;
import pl.kudlacze.core.config.CoreConfig;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcItems;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.db.KeyValueStore;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.hooks.Bedrock;
import pl.kudlacze.core.hooks.MetaReader;
import pl.kudlacze.core.hooks.Money;
import pl.kudlacze.core.hooks.Placeholders;
import pl.kudlacze.core.hooks.RankGranter;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.player.PlayerDirectory;
import pl.kudlacze.core.util.Tasks;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Wspólne usługi dostępne dla wszystkich modułów. Hooki do pluginów zewnętrznych mają
 * wersje „puste”, gdy danego pluginu nie ma (np. w testach).
 */
public final class CoreContext {

    public final JavaPlugin plugin;
    public final CoreConfig config;
    public final Messages messages;
    public final Tasks tasks;
    public final Clock clock;
    public final Sql sql;
    public final KeyValueStore kv;
    public final PlayerDirectory players;
    public final Placeholders placeholders;
    public final KcService kc;
    public final KcItems kcItems;
    /** Ustawiany po wykryciu hooków (formularze Bedrock wymagają Floodgate). */
    public MenuService menus;

    public MetaReader meta = MetaReader.NONE;
    /** Null, gdy LuckPerms nie jest dostępny (moduł rang wtedy się nie włączy). */
    public RankGranter ranks;
    public Bedrock bedrock = Bedrock.NONE;
    public Money money = Money.NONE;

    private final Map<Class<?>, Object> services = new HashMap<>();

    public CoreContext(JavaPlugin plugin, CoreConfig config, Messages messages, Tasks tasks, Clock clock, Sql sql,
                       KcItems kcItems) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.tasks = tasks;
        this.clock = clock;
        this.sql = sql;
        this.kv = new KeyValueStore(sql);
        this.players = new PlayerDirectory(sql);
        this.placeholders = new Placeholders();
        this.kc = new KcService(new pl.kudlacze.core.currency.KcRepository(sql), tasks, clock, config.server());
        this.kcItems = kcItems;
    }

    /** Rejestr usług modułów (np. serwis sezonu używany przez smoka i hologram rankingu). */
    public <T> void provide(Class<T> type, T service) {
        services.put(type, service);
    }

    public <T> T service(Class<T> type) {
        return type.cast(services.get(type));
    }

    public String server() {
        return config.server();
    }
}
