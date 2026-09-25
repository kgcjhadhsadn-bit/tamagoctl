package pl.kudlacze.core.lobby;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.module.CoreModule;

/**
 * Lobby: stały punkt spawnu (także przy pierwszym wejściu), brak obrażeń i głodu,
 * brak niszczenia (poza {@code kudlacze.lobby.buduj}), ratunek z pustki, tryb przygody.
 */
public final class LobbyModule implements CoreModule, Listener {

    public static final String PERM_BUILD = "kudlacze.lobby.buduj";

    private final CoreContext ctx;
    private Location spawn;
    private int voidY;

    public LobbyModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "lobby";
    }

    @Override
    public void enable() {
        World world = Bukkit.getWorlds().getFirst();
        spawn = new Location(world,
                ctx.config.decimal("lobby.spawn.x", 0.5), ctx.config.decimal("lobby.spawn.y", -60),
                ctx.config.decimal("lobby.spawn.z", 0.5), (float) ctx.config.decimal("lobby.spawn.yaw", 0),
                (float) ctx.config.decimal("lobby.spawn.pitch", 0));
        voidY = ctx.config.integer("lobby.pustka-y", -70);
        world.setSpawnLocation(spawn);
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
    }

    public Location spawn() {
        return spawn.clone();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        event.setSpawnLocation(spawn());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission(PERM_BUILD)) {
            p.setGameMode(GameMode.ADVENTURE);
        }
        p.setFoodLevel(20);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(spawn());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!event.getPlayer().hasPermission(PERM_BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.getPlayer().hasPermission(PERM_BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!event.getPlayer().hasPermission(PERM_BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getY() < voidY) {
            event.getPlayer().teleport(spawn());
        }
    }
}
