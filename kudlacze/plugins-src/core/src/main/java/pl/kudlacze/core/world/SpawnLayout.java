package pl.kudlacze.core.world;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Układ spawnu survival/seasons względem środka placu (sx, sy, sz):
 * <pre>
 *   plac 41x41 (promień 20) na wysokości sy-1, czyszczenie 12 bloków nad placem
 *   strefa VIP: x+22..x+36, z-7..z+7 (budynek z przeszklonymi ścianami, wejście od placu)
 *   expiarka:   dół 5x5 w środku strefy VIP (x+27..x+31, z-2..z+2), 3 bloki w głąb
 *   NPC:        Czarodziej Kudłacz (0,-6), Kustosz (-6,0), Kwatermistrz (0,+6), Skarbnik (VIP: x+33, z)
 * </pre>
 * Te same przesunięcia są dostępne w bootstrapie jako {sx+N} itd.
 */
public record SpawnLayout(World world, int sx, int sy, int sz) {

    public static final int PLAZA_RADIUS = 20;
    public static final int VIP_MIN_X = 22;
    public static final int VIP_MAX_X = 36;
    public static final int VIP_HALF_Z = 7;
    public static final int VIP_HEIGHT = 7;

    public Location center() {
        return new Location(world, sx + 0.5, sy, sz + 0.5);
    }

    public int vipMinX() {
        return sx + VIP_MIN_X;
    }

    public int vipMaxX() {
        return sx + VIP_MAX_X;
    }

    public int vipMinZ() {
        return sz - VIP_HALF_Z;
    }

    public int vipMaxZ() {
        return sz + VIP_HALF_Z;
    }

    /** Środek dołu expiarki (poziom podłogi dołu). */
    public Location expiarka() {
        return new Location(world, sx + 29.5, sy - 3, sz + 0.5);
    }

    public boolean insideVip(Location l) {
        return l.getWorld() == world && l.getBlockX() >= vipMinX() && l.getBlockX() <= vipMaxX()
                && l.getBlockZ() >= vipMinZ() && l.getBlockZ() <= vipMaxZ()
                && l.getBlockY() >= sy - 4 && l.getBlockY() <= sy + VIP_HEIGHT;
    }

    public String serialize() {
        return world.getName() + "," + sx + "," + sy + "," + sz;
    }

    public static SpawnLayout parse(String value, java.util.function.Function<String, World> worlds) {
        String[] p = value.split(",");
        World w = worlds.apply(p[0]);
        if (w == null) {
            return null;
        }
        return new SpawnLayout(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }
}
