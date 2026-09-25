package pl.kudlacze.core.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Buduje plac spawnu survival/seasons przy punkcie spawnu świata (raz na świat — po nowej
 * edycji/sezonie świat ma nowe UID, więc plac powstaje od nowa). Układ opisuje {@link SpawnLayout}.
 */
public final class SpawnBuilder {

    private static final int CLEAR_ABOVE = 12;
    private static final int FOUNDATION = 6;

    private SpawnBuilder() {
    }

    /** Wyznacza środek placu przy spawnie świata (na powierzchni) i buduje całość. */
    public static SpawnLayout build(World world) {
        int x = world.getSpawnLocation().getBlockX();
        int z = world.getSpawnLocation().getBlockZ();
        int surface = Math.max(world.getSeaLevel(), world.getHighestBlockYAt(x, z));
        SpawnLayout layout = new SpawnLayout(world, x, surface + 1, z);
        buildPlaza(layout);
        buildVip(layout);
        world.setSpawnLocation(x, surface + 1, z);
        return layout;
    }

    private static void buildPlaza(SpawnLayout l) {
        World w = l.world();
        int floor = l.sy() - 1;
        int r = SpawnLayout.PLAZA_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = l.sx() + dx;
                int z = l.sz() + dz;
                for (int y = floor + 1; y <= floor + CLEAR_ABOVE; y++) {
                    set(w, x, y, z, Material.AIR);
                }
                Material top;
                if (Math.abs(dx) == r || Math.abs(dz) == r) {
                    top = Material.PURPUR_BLOCK;
                } else if (dx == 0 || dz == 0) {
                    top = Material.SMOOTH_STONE;
                } else if (dx % 5 == 0 && dz % 5 == 0) {
                    top = Material.SEA_LANTERN;
                } else {
                    top = Material.POLISHED_ANDESITE;
                }
                set(w, x, floor, z, top);
                for (int y = floor - 1; y >= floor - FOUNDATION; y--) {
                    Material cur = w.getBlockAt(x, y, z).getType();
                    if (cur.isAir() || cur == Material.WATER || cur == Material.LAVA || !cur.isSolid()) {
                        set(w, x, y, z, Material.STONE);
                    }
                }
            }
        }
        // środek placu i obrzeże z latarniami (oświetlenie — brak mobów na spawnie)
        set(w, l.sx(), floor, l.sz(), Material.AMETHYST_BLOCK);
        for (int dx = -r; dx <= r; dx += 10) {
            set(w, l.sx() + dx, floor + 1, l.sz() - r, Material.LANTERN);
            set(w, l.sx() + dx, floor + 1, l.sz() + r, Material.LANTERN);
            set(w, l.sx() - r, floor + 1, l.sz() + dx, Material.LANTERN);
        }
    }

    private static void buildVip(SpawnLayout l) {
        World w = l.world();
        int floor = l.sy() - 1;
        int top = floor + SpawnLayout.VIP_HEIGHT;
        for (int x = l.vipMinX(); x <= l.vipMaxX(); x++) {
            for (int z = l.vipMinZ(); z <= l.vipMaxZ(); z++) {
                boolean wall = x == l.vipMinX() || x == l.vipMaxX() || z == l.vipMinZ() || z == l.vipMaxZ();
                for (int y = floor - FOUNDATION; y < floor; y++) {
                    Material cur = w.getBlockAt(x, y, z).getType();
                    if (cur.isAir() || !cur.isSolid()) {
                        set(w, x, y, z, Material.STONE);
                    }
                }
                set(w, x, floor, z, wall ? Material.PURPUR_PILLAR : Material.PURPUR_BLOCK);
                for (int y = floor + 1; y < top; y++) {
                    set(w, x, y, z, wall ? Material.PURPLE_STAINED_GLASS : Material.AIR);
                }
                set(w, x, top, z, wall ? Material.PURPUR_SLAB : Material.PURPLE_STAINED_GLASS);
                for (int y = top + 1; y <= floor + CLEAR_ABOVE; y++) {
                    set(w, x, y, z, Material.AIR);
                }
            }
        }
        // wejście od strony placu (x = vipMinX), 3x3
        for (int z = l.sz() - 1; z <= l.sz() + 1; z++) {
            for (int y = floor + 1; y <= floor + 3; y++) {
                set(w, l.vipMinX(), y, z, Material.AIR);
            }
        }
        // oświetlenie strefy
        set(w, l.vipMinX() + 2, floor, l.vipMinZ() + 2, Material.SEA_LANTERN);
        set(w, l.vipMaxX() - 2, floor, l.vipMinZ() + 2, Material.SEA_LANTERN);
        set(w, l.vipMinX() + 2, floor, l.vipMaxZ() - 2, Material.SEA_LANTERN);
        set(w, l.vipMaxX() - 2, floor, l.vipMaxZ() - 2, Material.SEA_LANTERN);
        // dół expiarki 5x5, 3 w głąb, dno z obsydianu
        int cx = l.expiarka().getBlockX();
        int cz = l.expiarka().getBlockZ();
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                for (int y = floor - 3; y <= floor; y++) {
                    set(w, x, y, z, Material.AIR);
                }
                set(w, x, floor - 4, z, Material.OBSIDIAN);
            }
        }
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) {
                    for (int y = floor - 4; y < floor; y++) {
                        set(w, x, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    }
                    set(w, x, floor, z, Material.CRYING_OBSIDIAN);
                }
            }
        }
    }

    private static void set(World w, int x, int y, int z, Material m) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
            return;
        }
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != m) {
            b.setType(m, false);
        }
    }
}
