package pl.kudlacze.core.bootstrap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.hooks.WorldGuardBridge;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.world.SpawnBuilder;
import pl.kudlacze.core.world.SpawnLayout;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Przygotowanie świata serwera:
 * <ol>
 *   <li>survival/seasons: budowa placu spawnu przy nowym świecie (rozpoznanie po UID świata),
 *       regiony WorldGuard „spawn” (promień ochrony, brak działek) i „strefa_vip”,</li>
 *   <li>wykonanie komend z pliku {@code bootstrap.txt} (NPC, hologramy, granice, pregeneracja),
 *       gdy zmieniła się jego treść albo świat został odbudowany.</li>
 * </ol>
 * W komendach działają placeholdery {@code {swiat}}, {@code {sx}}, {@code {sy+1}}, {@code {sz-6.5}}.
 * Dyrektywa {@code @zaladuj x1 z1 x2 z2} ładuje chunki obszaru (bilet pluginu do końca bootstrapu) — potrzebne
 * dla {@code setblock}/{@code fill}, bo w 26.x chunki spawnu nie są stale załadowane.
 */
public final class BootstrapModule implements CoreModule {

    private static final Pattern COORD = Pattern.compile("\\{(sx|sy|sz)([+-]\\d+(?:\\.\\d+)?)?}");

    private final CoreContext ctx;
    private BukkitTask runner;

    public BootstrapModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "bootstrap";
    }

    @Override
    public void enable() {
        KudlaczeCore.command(ctx, "kudlaczeadmin", new AdminCommand());
        // start po chwili — FancyNpcs/DecentHolograms/Chunky muszą się w pełni załadować
        Bukkit.getScheduler().runTaskLater(ctx.plugin, () -> prepare(false), 100L);
    }

    @Override
    public void disable() {
        if (runner != null) {
            runner.cancel();
        }
    }

    /** Buduje spawn (jeśli trzeba) i wykonuje bootstrap (jeśli zmieniony, odbudowany świat albo force). */
    public void prepare(boolean force) {
        World world = Bukkit.getWorlds().getFirst();
        String server = ctx.server();
        String worldToken = world.getUID().toString();
        boolean needsSpawn = ctx.config.bool("bootstrap.buduj-spawn", false);
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> Map.of(
                "spawn", ctx.kv.get("spawn." + server).orElse(""),
                "swiat", ctx.kv.get("spawn." + server + ".swiat").orElse(""),
                "hash", ctx.kv.get("bootstrap." + server + ".hash").orElse(""))), state -> {
            SpawnLayout layout = null;
            boolean rebuilt = false;
            if (needsSpawn) {
                if (!worldToken.equals(state.get("swiat")) || state.get("spawn").isEmpty()) {
                    ctx.plugin.getLogger().info("Nowy świat — buduję plac spawnu…");
                    layout = SpawnBuilder.build(world);
                    defineRegions(layout);
                    rebuilt = true;
                    SpawnLayout saved = layout;
                    ctx.tasks.runAsync(() -> {
                        ctx.kv.put("spawn." + server, saved.serialize());
                        ctx.kv.put("spawn." + server + ".swiat", worldToken);
                    });
                } else {
                    layout = SpawnLayout.parse(state.get("spawn"), Bukkit::getWorld);
                    if (layout != null) {
                        defineRegions(layout); // idempotentne — aktualizuje flagi po zmianach w kodzie
                    }
                }
                if (layout != null) {
                    ctx.provide(SpawnLayout.class, layout);
                }
            }
            runCommands(layout, world, state.get("hash"), force || rebuilt);
        }, e -> ctx.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Bootstrap nie powiódł się", e));
    }

    private void defineRegions(SpawnLayout l) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return;
        }
        int protect = ctx.config.integer("bootstrap.ochrona-spawnu-promien", 150);
        World w = l.world();
        WorldGuardBridge wg = new WorldGuardBridge();
        wg.defineRegion(w, "spawn", l.sx() - protect, w.getMinHeight(), l.sz() - protect,
                l.sx() + protect, w.getMaxHeight() - 1, l.sz() + protect, 5, WorldGuardBridge.spawnFlags());
        wg.defineRegion(w, "strefa_vip", l.vipMinX(), l.sy() - 5, l.vipMinZ(), l.vipMaxX(),
                l.sy() + SpawnLayout.VIP_HEIGHT, l.vipMaxZ(), 10, WorldGuardBridge.vipFlags());
    }

    private void runCommands(SpawnLayout layout, World world, String lastHash, boolean force) {
        File file = new File(ctx.plugin.getDataFolder(), "bootstrap.txt");
        if (!file.exists()) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ctx.plugin.getLogger().warning("Nie można odczytać bootstrap.txt: " + e.getMessage());
            return;
        }
        String hash = sha256(String.join("\n", lines));
        if (!force && hash.equals(lastHash)) {
            return;
        }
        UnaryOperator<String> expand = line -> expand(line, layout, world);
        Deque<String> queue = new ArrayDeque<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                queue.add(expand.apply(t));
            }
        }
        ctx.plugin.getLogger().info("Wykonuję bootstrap.txt (" + queue.size() + " komend)…");
        CommandSender console = Bukkit.getConsoleSender();
        java.util.List<long[]> tickets = new java.util.ArrayList<>();
        runner = Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> {
            String cmd = queue.poll();
            if (cmd != null && cmd.startsWith("@zaladuj")) {
                loadArea(world, cmd, tickets);
                return;
            }
            if (cmd == null) {
                for (long[] t : tickets) {
                    world.removePluginChunkTicket((int) t[0], (int) t[1], ctx.plugin);
                }
                runner.cancel();
                runner = null;
                ctx.tasks.runAsync(() -> ctx.kv.put("bootstrap." + ctx.server() + ".hash", hash));
                ctx.plugin.getLogger().info("Bootstrap zakończony.");
                return;
            }
            try {
                Bukkit.dispatchCommand(console, cmd);
            } catch (RuntimeException e) {
                ctx.plugin.getLogger().warning("Komenda bootstrapu nie powiodła się: " + cmd + " — " + e.getMessage());
            }
        }, 1L, 2L);
    }

    /** {@code @zaladuj x1 z1 x2 z2} (współrzędne bloków) — synchroniczne załadowanie chunków obszaru. */
    private void loadArea(World world, String directive, java.util.List<long[]> tickets) {
        String[] p = directive.trim().split("\\s+");
        if (p.length != 5) {
            ctx.plugin.getLogger().warning("Zła dyrektywa bootstrapu: " + directive);
            return;
        }
        int x1 = (int) Math.floor(Double.parseDouble(p[1])) >> 4;
        int z1 = (int) Math.floor(Double.parseDouble(p[2])) >> 4;
        int x2 = (int) Math.floor(Double.parseDouble(p[3])) >> 4;
        int z2 = (int) Math.floor(Double.parseDouble(p[4])) >> 4;
        for (int cx = Math.min(x1, x2); cx <= Math.max(x1, x2); cx++) {
            for (int cz = Math.min(z1, z2); cz <= Math.max(z1, z2); cz++) {
                if (world.addPluginChunkTicket(cx, cz, ctx.plugin)) {
                    tickets.add(new long[]{cx, cz});
                }
            }
        }
    }

    /** Podstawia {swiat} i współrzędne placu (także z przesunięciem, np. {sx+5.5}). */
    public static String expand(String line, SpawnLayout layout, World world) {
        String out = line.replace("{swiat}", layout != null ? layout.world().getName() : world.getName());
        if (layout == null) {
            return out;
        }
        Matcher m = COORD.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            double base = switch (m.group(1)) {
                case "sx" -> layout.sx();
                case "sy" -> layout.sy();
                default -> layout.sz();
            };
            double offset = m.group(2) == null ? 0 : Double.parseDouble(m.group(2));
            double v = base + offset;
            String formatted = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
            m.appendReplacement(sb, formatted);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** /kudlaczeadmin bootstrap — ponowne wykonanie bootstrapu (np. po ręcznych zmianach NPC). */
    private final class AdminCommand extends BaseCommand {
        AdminCommand() {
            super(BootstrapModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            if (!requirePermission(sender, "kudlacze.admin")) {
                return;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("bootstrap")) {
                prepare(true);
                msg.send(sender, "admin.bootstrap", Messages.text("serwer", ctx.server()));
                return;
            }
            if (args.length >= 1 && args[0].equalsIgnoreCase("moduly")) {
                sender.sendMessage(msg.parse("<prefix><gray>Moduły: <white>"
                        + ((KudlaczeCore) ctx.plugin).enabledModules()));
                return;
            }
            msg.send(sender, "admin.uzycie");
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 ? List.of("bootstrap", "moduly") : List.of();
        }
    }
}
