package pl.kudlacze.core.lumberjack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wyszukiwanie całego drzewa od ściętego kloca (BFS w sąsiedztwie 26 bloków, bez schodzenia
 * poniżej poziomu ścięcia). Żeby nie ścinać budowli z drewna, drzewo musi mieć w pobliżu
 * minimalną liczbę liści (naturalne liście).
 */
public final class TreeFeller {

    /** Widok świata do testów i do adaptera Bukkit. */
    public interface Grid {
        boolean isLog(int x, int y, int z);

        boolean isNaturalLeaves(int x, int y, int z);
    }

    public record Pos(int x, int y, int z) {
    }

    private TreeFeller() {
    }

    /**
     * @return kloce drzewa (bez bloku startowego) w kolejności od najbliższych; pusta lista, gdy
     * to nie wygląda na drzewo (za mało liści)
     */
    public static List<Pos> findTree(Grid grid, Pos start, int limit, int minLeaves) {
        // do rozpoznania drzewa szukamy szerzej niż limit ścinania (korona wysokich drzew
        // bywa daleko od dolnych kloców), ścinamy tylko 'limit' najbliższych
        int explore = limit * 4;
        List<Pos> logs = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        Deque<Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && logs.size() < explore) {
            Pos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        Pos n = new Pos(p.x() + dx, p.y() + dy, p.z() + dz);
                        if (n.y() < start.y() || !seen.add(n) || !grid.isLog(n.x(), n.y(), n.z())) {
                            continue;
                        }
                        logs.add(n);
                        queue.add(n);
                        if (logs.size() >= explore) {
                            break;
                        }
                    }
                }
            }
        }
        if (countLeaves(grid, start, logs, minLeaves) < minLeaves) {
            return List.of();
        }
        return logs.size() > limit ? List.copyOf(logs.subList(0, limit)) : logs;
    }

    private static int countLeaves(Grid grid, Pos start, List<Pos> logs, int enough) {
        Set<Pos> counted = new HashSet<>();
        List<Pos> all = new ArrayList<>(logs);
        all.add(start);
        for (Pos p : all) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Pos n = new Pos(p.x() + dx, p.y() + dy, p.z() + dz);
                        if (grid.isNaturalLeaves(n.x(), n.y(), n.z()) && counted.add(n) && counted.size() >= enough) {
                            return counted.size();
                        }
                    }
                }
            }
        }
        return counted.size();
    }
}
