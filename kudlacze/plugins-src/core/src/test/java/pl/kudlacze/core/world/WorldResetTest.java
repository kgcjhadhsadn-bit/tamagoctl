package pl.kudlacze.core.world;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldResetTest {

    @TempDir
    Path root;

    /** Układ jak w kontenerze: /data/world (26.x: wymiary w dimensions/), /data/plugins/... */
    private void layout() throws Exception {
        Files.createDirectories(root.resolve("world/dimensions/minecraft/overworld/region"));
        Files.writeString(root.resolve("world/dimensions/minecraft/overworld/region/r.0.0.mca"), "x");
        Files.createDirectories(root.resolve("world/players/data"));
        Files.writeString(root.resolve("world/level.dat"), "x");
        Files.writeString(root.resolve("world/session.lock"), "x");
        Files.createDirectories(root.resolve("world_nether")); // stary układ (sprzed 26.x)
        Files.createDirectories(root.resolve("seasons_boss"));
        for (String w : List.of("world", "world_nether", "world_the_end")) {
            Path dir = root.resolve("plugins/WorldGuard/worlds/" + w);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("regions.yml"), "regions: {}");
            Files.writeString(dir.resolve("config.yml"), "x: 1");
        }
        Files.createDirectories(root.resolve("plugins/Chunky/tasks"));
        Files.writeString(root.resolve("plugins/Chunky/tasks/world.properties"), "x=1");
        Files.createDirectories(root.resolve("plugins/Essentials/userdata"));
        YamlConfiguration user = new YamlConfiguration();
        user.set("money", "1234.5");
        user.set("homes.dom.world", "world");
        user.set("homes.dom.x", 10.0);
        user.set("logoutlocation.world", "world");
        user.set("last-account-name", "Ala");
        user.save(root.resolve("plugins/Essentials/userdata/ala.yml").toFile());
    }

    @Test
    void editionResetArchivesWorldAndCleansPluginData() throws Exception {
        layout();
        WorldReset.Result r = WorldReset.execute(new WorldReset.Plan("edycja", 3, true, true, List.of()),
                root, root.resolve("plugins"), "world", LocalDateTime.of(2026, 12, 1, 12, 0), null, true);
        assertEquals(List.of("world", "world_nether"), r.archived());
        Path archived = root.resolve("archiwum/edycja-3-20261201-1200/world");
        assertTrue(Files.exists(archived.resolve("level.dat")));
        assertTrue(Files.exists(archived.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertFalse(Files.exists(archived.resolve("session.lock")));
        assertFalse(Files.exists(root.resolve("world")));
        assertTrue(Files.exists(root.resolve("seasons_boss")), "światy spoza planu zostają");
        assertEquals(3, r.regionFiles());
        assertTrue(Files.exists(root.resolve("plugins/WorldGuard/worlds/world/config.yml")));
        assertFalse(Files.exists(root.resolve("plugins/WorldGuard/worlds/world/regions.yml")));
        assertFalse(Files.exists(root.resolve("plugins/Chunky/tasks/world.properties")));
        assertEquals(1, r.essentialsUsers());
        YamlConfiguration user = YamlConfiguration.loadConfiguration(root.resolve("plugins/Essentials/userdata/ala.yml").toFile());
        assertFalse(user.contains("money"));
        assertFalse(user.contains("homes"));
        assertFalse(user.contains("logoutlocation"));
        assertEquals("Ala", user.getString("last-account-name"));
    }

    @Test
    void seasonResetDeletesWorldsIncludingExtraOnes() throws Exception {
        layout();
        WorldReset.Result r = WorldReset.execute(new WorldReset.Plan("sezon", 7, false, true, List.of("seasons_boss")),
                root, root.resolve("plugins"), "world", LocalDateTime.of(2026, 10, 1, 12, 0), null, true);
        assertEquals(List.of("world", "world_nether", "seasons_boss"), r.deleted());
        assertFalse(Files.exists(root.resolve("world")));
        assertFalse(Files.exists(root.resolve("seasons_boss")));
        assertFalse(Files.exists(root.resolve("archiwum")));
    }

    @Test
    void worldFoldersAreLeftToPreStartScript() throws Exception {
        layout();
        WorldReset.Result r = WorldReset.execute(new WorldReset.Plan("edycja", 3, true, true, List.of()),
                root, root.resolve("plugins"), "world", LocalDateTime.of(2026, 12, 1, 12, 0), null, false);
        assertTrue(r.archived().isEmpty() && r.deleted().isEmpty());
        assertTrue(Files.exists(root.resolve("world/level.dat")));
        assertFalse(Files.exists(root.resolve("plugins/WorldGuard/worlds/world/regions.yml")));
        assertEquals(1, r.essentialsUsers());
    }

    @Test
    void markerRoundTrip() throws Exception {
        File folder = root.resolve("plugins/KudlaczeCore").toFile();
        WorldReset.Plan plan = new WorldReset.Plan("edycja", 4, true, false, List.of("a", "b"));
        WorldReset.schedule(folder, plan);
        assertTrue(WorldReset.isPending(folder));
        Path marker = folder.toPath().resolve(WorldReset.MARKER);
        assertEquals(plan, WorldReset.read(marker));
        assertFalse(WorldReset.worldsAlreadyReset(marker));
        Files.writeString(marker, "swiat-zresetowany=true\n", java.nio.file.StandardOpenOption.APPEND);
        assertTrue(WorldReset.worldsAlreadyReset(marker));
        assertEquals(plan, WorldReset.read(marker));
    }

    @Test
    void levelNameFromServerProperties() throws Exception {
        Path props = root.resolve("server.properties");
        Files.writeString(props, "motd=x\nlevel-name=swiat\n");
        assertEquals("swiat", WorldReset.levelName(props));
        assertEquals("world", WorldReset.levelName(root.resolve("brak.properties")));
    }
}
