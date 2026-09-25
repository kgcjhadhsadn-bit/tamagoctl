package pl.kudlacze.core.hooks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Regiony WorldGuard zakładane przez plugin core (spawn, strefa VIP) i liczenie działek
 * ProtectionStones gracza. Ładowana tylko, gdy WorldGuard jest włączony.
 */
public final class WorldGuardBridge {

    /** Tworzy albo aktualizuje region prostopadłościenny z flagami. */
    public void defineRegion(World world, String id, int x1, int y1, int z1, int x2, int y2, int z2, int priority,
                             Map<StateFlag, StateFlag.State> flags) {
        RegionManager rm = manager(world);
        if (rm == null) {
            return;
        }
        ProtectedRegion region = new ProtectedCuboidRegion(id,
                BlockVector3.at(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)),
                BlockVector3.at(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)));
        region.setPriority(priority);
        flags.forEach(region::setFlag);
        rm.addRegion(region);
    }

    /**
     * Flagi spawnu (bezpieczna strefa — przy zasadzie „śmierć = kick” nowi gracze nie mogą ginąć
     * na placu): brak PvP, mobów, obrażeń od mobów, wybuchów i ognia.
     */
    public static Map<StateFlag, StateFlag.State> spawnFlags() {
        return Map.of(Flags.PVP, StateFlag.State.DENY, Flags.TNT, StateFlag.State.DENY,
                Flags.CREEPER_EXPLOSION, StateFlag.State.DENY, Flags.FIRE_SPREAD, StateFlag.State.DENY,
                Flags.LAVA_FIRE, StateFlag.State.DENY, Flags.OTHER_EXPLOSION, StateFlag.State.DENY,
                Flags.MOB_SPAWNING, StateFlag.State.DENY, Flags.MOB_DAMAGE, StateFlag.State.DENY);
    }

    /**
     * Strefa VIP: jak spawn, ale z dozwolonym spawnem mobów — WorldGuard domyślnie blokuje w regionach
     * z mob-spawning=deny także moby od pluginów (mobs.block-plugin-spawning), a expiarka ich potrzebuje.
     * Obrażenia od mobów nadal wyłączone.
     */
    public static Map<StateFlag, StateFlag.State> vipFlags() {
        java.util.HashMap<StateFlag, StateFlag.State> m = new java.util.HashMap<>(spawnFlags());
        m.put(Flags.MOB_SPAWNING, StateFlag.State.ALLOW);
        return m;
    }

    /** Liczba działek ProtectionStones (regiony „ps…”), których gracz jest właścicielem w danym świecie. */
    public int countPlots(Player player, World world) {
        RegionManager rm = manager(world);
        if (rm == null) {
            return 0;
        }
        int count = 0;
        for (ProtectedRegion r : rm.getRegions().values()) {
            if (r.getId().startsWith("ps") && r.getOwners().contains(player.getUniqueId())) {
                count++;
            }
        }
        return count;
    }

    private static RegionManager manager(World world) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }
}
