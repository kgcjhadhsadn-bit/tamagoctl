package pl.kudlacze.core.world;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Zaplanowane czyszczenie światów (nowa edycja survivalu, nowy sezon). Moduł zapisuje znacznik
 * {@value #MARKER} i wyłącza serwer. Przy następnym starcie:
 * <ol>
 *   <li>{@code scripts/container/pre-start.sh} (entrypoint kontenera, PRZED startem Javy) archiwizuje albo
 *       usuwa folder świata — Minecraft 26.x wczytuje level.dat i seed ({@code world_gen_settings.dat})
 *       jeszcze przed onLoad() pluginów, więc usunięcie świata z pluginu nie zmieniłoby seeda;
 *       skrypt dopisuje {@code swiat-zresetowany=true},</li>
 *   <li>{@link #runPending(JavaPlugin)} w onLoad() (zanim pluginy wczytają dane) czyści:</li>
 * </ol>
 * <ul>
 *   <li>świat — tylko gdy skrypt przed startem nie zadziałał (np. serwer poza Dockerem; wtedy seed zostaje),</li>
 *   <li>regiony WorldGuard (a więc i działki ProtectionStones) tych światów,</li>
 *   <li>zadania pregeneracji Chunky (inaczej wznowiłyby się od starej pozycji),</li>
 *   <li>EssentialsX: domy, ostatnie lokalizacje i (opcjonalnie) saldo w userdata.</li>
 * </ul>
 * Nowy świat dostaje losowy seed (pusty {@code level-seed} w server.properties).
 */
public final class WorldReset {

    public static final String MARKER = "reset-swiata.properties";

    /** Co zresetować. {@code type}: edycja | sezon. */
    public record Plan(String type, int number, boolean archive, boolean resetMoney, List<String> extraWorlds) {
    }

    /** Wynik resetu (log i testy). */
    public record Result(List<String> archived, List<String> deleted, int essentialsUsers, int regionFiles) {
    }

    private WorldReset() {
    }

    /** Zapisuje znacznik — reset wykona się przy następnym starcie serwera. */
    public static void schedule(File dataFolder, Plan plan) throws IOException {
        Properties p = new Properties();
        p.setProperty("typ", plan.type());
        p.setProperty("numer", String.valueOf(plan.number()));
        p.setProperty("archiwizuj", String.valueOf(plan.archive()));
        p.setProperty("resetuj-pieniadze", String.valueOf(plan.resetMoney()));
        p.setProperty("dodatkowe-swiaty", String.join(",", plan.extraWorlds()));
        Files.createDirectories(dataFolder.toPath());
        Path tmp = dataFolder.toPath().resolve(MARKER + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "Kudłacze — zaplanowany reset świata (wykonywany przy starcie serwera)");
        }
        Files.move(tmp, dataFolder.toPath().resolve(MARKER), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static boolean isPending(File dataFolder) {
        return new File(dataFolder, MARKER).exists();
    }

    /** Wykonuje reset, jeśli w folderze pluginu leży znacznik zaplanowany przed restartem. */
    public static void runPending(JavaPlugin plugin) {
        File marker = new File(plugin.getDataFolder(), MARKER);
        if (!marker.exists()) {
            return;
        }
        Logger log = plugin.getLogger();
        try {
            Plan plan = read(marker.toPath());
            boolean worldsDone = worldsAlreadyReset(marker.toPath());
            if (!worldsDone) {
                log.severe("Świat nie został usunięty przed startem (brak scripts/container/pre-start.sh w entrypoincie)"
                        + " — usuwam go teraz, ale nowy świat dostanie TEN SAM seed.");
            }
            Path worlds = Bukkit.getWorldContainer().toPath();
            Path plugins = plugin.getDataFolder().getParentFile().toPath();
            String level = levelName(Path.of("server.properties"));
            log.warning("=== RESET ŚWIATA: " + plan.type() + " #" + plan.number() + " (świat '" + level + "') ===");
            Result r = execute(plan, worlds, plugins, level, LocalDateTime.now(), log, !worldsDone);
            log.warning("Reset zakończony — archiwum: " + r.archived() + ", usunięto: " + r.deleted()
                    + ", pliki regionów: " + r.regionFiles() + ", gracze Essentials: " + r.essentialsUsers());
            Files.move(marker.toPath(), marker.toPath().resolveSibling("reset-swiata.wykonany.properties"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Reset świata nie powiódł się: " + e.getMessage(), e);
        }
    }

    static Plan read(Path marker) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        List<String> extra = Arrays.stream(p.getProperty("dodatkowe-swiaty", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new Plan(p.getProperty("typ", "reset"), Integer.parseInt(p.getProperty("numer", "0")),
                Boolean.parseBoolean(p.getProperty("archiwizuj", "false")),
                Boolean.parseBoolean(p.getProperty("resetuj-pieniadze", "true")), extra);
    }

    static boolean worldsAlreadyReset(Path marker) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(marker, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return Boolean.parseBoolean(p.getProperty("swiat-zresetowany", "false"));
    }

    static String levelName(Path serverProperties) {
        Properties p = new Properties();
        if (Files.exists(serverProperties)) {
            try (Reader r = Files.newBufferedReader(serverProperties, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
                // domyślna nazwa
            }
        }
        String name = p.getProperty("level-name", "world").trim();
        return name.isEmpty() ? "world" : name;
    }

    /** Właściwy reset na plikach — bez zależności od działającego serwera (testy jednostkowe). */
    public static Result execute(Plan plan, Path worldContainer, Path pluginsDir, String level, LocalDateTime now,
                                 Logger log, boolean resetWorldFolders) throws IOException {
        Set<String> worldNames = new LinkedHashSet<>(List.of(level, level + "_nether", level + "_the_end"));
        worldNames.addAll(plan.extraWorlds());

        List<String> archived = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        Path archiveDir = worldContainer.resolve("archiwum").resolve(plan.type() + "-" + plan.number() + "-"
                + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")));
        for (String name : resetWorldFolders ? worldNames : Set.<String>of()) {
            Path dir = worldContainer.resolve(name);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Files.deleteIfExists(dir.resolve("session.lock"));
            if (plan.archive()) {
                Files.createDirectories(archiveDir);
                Files.move(dir, archiveDir.resolve(name));
                archived.add(name);
            } else {
                deleteRecursively(dir);
                deleted.add(name);
            }
        }

        int regionFiles = 0;
        for (String name : worldNames) {
            if (Files.deleteIfExists(pluginsDir.resolve("WorldGuard/worlds").resolve(name).resolve("regions.yml"))) {
                regionFiles++;
            }
        }

        Path chunkyTasks = pluginsDir.resolve("Chunky/tasks");
        if (Files.isDirectory(chunkyTasks)) {
            try (DirectoryStream<Path> tasks = Files.newDirectoryStream(chunkyTasks, "*.properties")) {
                for (Path t : tasks) {
                    Files.delete(t);
                }
            }
        }

        int users = 0;
        Path userdata = pluginsDir.resolve("Essentials/userdata");
        if (Files.isDirectory(userdata)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(userdata, "*.yml")) {
                for (Path f : files) {
                    if (cleanEssentialsUser(f, plan.resetMoney())) {
                        users++;
                    }
                }
            }
        }
        if (log != null && !archived.isEmpty()) {
            log.info("Archiwum świata: " + archiveDir);
        }
        return new Result(archived, deleted, users, regionFiles);
    }

    /** Usuwa z pliku gracza EssentialsX domy, ostatnie lokalizacje i saldo (wraca saldo startowe). */
    static boolean cleanEssentialsUser(Path file, boolean resetMoney) throws IOException {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file.toFile());
        boolean changed = false;
        List<String> keys = new ArrayList<>(List.of("homes", "lastlocation", "logoutlocation"));
        if (resetMoney) {
            keys.add("money");
        }
        for (String key : keys) {
            if (yml.contains(key)) {
                yml.set(key, null);
                changed = true;
            }
        }
        if (changed) {
            yml.save(file.toFile());
        }
        return changed;
    }

    static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
