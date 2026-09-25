package pl.kudlacze.core.lumberjack;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeFellerTest {

    /** Świat testowy: zbiory pozycji kloców i liści. */
    static final class Grid implements TreeFeller.Grid {
        final Set<TreeFeller.Pos> logs = new HashSet<>();
        final Set<TreeFeller.Pos> leaves = new HashSet<>();

        @Override
        public boolean isLog(int x, int y, int z) {
            return logs.contains(new TreeFeller.Pos(x, y, z));
        }

        @Override
        public boolean isNaturalLeaves(int x, int y, int z) {
            return leaves.contains(new TreeFeller.Pos(x, y, z));
        }

        /** Dąb: pień wysokości h od (0,0,0) i korona liści 5x5 na górze. */
        Grid oak(int h) {
            for (int y = 0; y < h; y++) {
                logs.add(new TreeFeller.Pos(0, y, 0));
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = h - 2; y <= h; y++) {
                        TreeFeller.Pos p = new TreeFeller.Pos(x, y, z);
                        if (!logs.contains(p)) {
                            leaves.add(p);
                        }
                    }
                }
            }
            return this;
        }
    }

    @Test
    void fellsWholeTrunkAboveCut() {
        Grid g = new Grid().oak(6);
        List<TreeFeller.Pos> tree = TreeFeller.findTree(g, new TreeFeller.Pos(0, 0, 0), 100, 4);
        assertEquals(5, tree.size());
        assertTrue(tree.contains(new TreeFeller.Pos(0, 5, 0)));
        assertFalse(tree.contains(new TreeFeller.Pos(0, 0, 0)), "blok startowy łamie gracz");
    }

    @Test
    void doesNotGoBelowCutLevel() {
        Grid g = new Grid().oak(6);
        List<TreeFeller.Pos> tree = TreeFeller.findTree(g, new TreeFeller.Pos(0, 3, 0), 100, 4);
        assertEquals(2, tree.size());
        assertTrue(tree.stream().allMatch(p -> p.y() > 3));
    }

    @Test
    void followsDiagonalBranches() {
        Grid g = new Grid().oak(5);
        g.logs.add(new TreeFeller.Pos(1, 3, 1));
        g.logs.add(new TreeFeller.Pos(2, 4, 2));
        List<TreeFeller.Pos> tree = TreeFeller.findTree(g, new TreeFeller.Pos(0, 0, 0), 100, 4);
        assertTrue(tree.contains(new TreeFeller.Pos(2, 4, 2)));
    }

    @Test
    void respectsBlockLimit() {
        Grid g = new Grid().oak(40);
        assertEquals(10, TreeFeller.findTree(g, new TreeFeller.Pos(0, 0, 0), 10, 4).size());
    }

    @Test
    void woodenBuildWithoutLeavesIsNotATree() {
        Grid g = new Grid();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 4; y++) {
                g.logs.add(new TreeFeller.Pos(x, y, 0));
            }
        }
        assertTrue(TreeFeller.findTree(g, new TreeFeller.Pos(0, 0, 0), 100, 4).isEmpty());
    }
}
