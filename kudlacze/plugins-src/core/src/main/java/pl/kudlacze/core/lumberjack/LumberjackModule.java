package pl.kudlacze.core.lumberjack;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.module.CoreModule;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * DRWAL: ścięcie całego drzewa jednym uderzeniem siekiery (uprawnienie {@code kudlacze.drwal}).
 * Każdy dodatkowy kloc przechodzi przez {@link BlockBreakEvent}, więc ochrona działek (WorldGuard/PS)
 * i logi CoreProtect działają normalnie. Kucanie wyłącza drwala.
 */
public final class LumberjackModule implements CoreModule, Listener {

    public static final String PERMISSION = "kudlacze.drwal";

    private final CoreContext ctx;
    private final Set<UUID> processing = new HashSet<>();
    private final Set<Material> axes = EnumSet.noneOf(Material.class);
    private int limit;
    private int minLeaves;
    private boolean sneakDisables;

    public LumberjackModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "drwal";
    }

    @Override
    public void enable() {
        limit = ctx.config.integer("drwal.limit-blokow", 96);
        minLeaves = ctx.config.integer("drwal.minimum-lisci", 4);
        sneakDisables = ctx.config.bool("drwal.kucanie-wylacza", true);
        for (String name : ctx.config.list("drwal.siekiery")) {
            Material m = Material.matchMaterial(name);
            if (m != null) {
                axes.add(m);
            }
        }
        if (axes.isEmpty()) {
            axes.addAll(List.of(Material.WOODEN_AXE, Material.STONE_AXE, Material.COPPER_AXE, Material.IRON_AXE,
                    Material.GOLDEN_AXE, Material.DIAMOND_AXE));
        }
        if (ctx.config.bool("drwal.siekiera-netherytowa", false)) {
            axes.add(Material.NETHERITE_AXE);
        } else {
            axes.remove(Material.NETHERITE_AXE);
        }
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
    }

    public Set<Material> allowedAxes() {
        return axes;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        if (processing.contains(player.getUniqueId()) || !Tag.LOGS.isTagged(origin.getType())
                || !player.hasPermission(PERMISSION) || (sneakDisables && player.isSneaking())) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!axes.contains(tool.getType())) {
            return;
        }
        World world = origin.getWorld();
        List<TreeFeller.Pos> tree = TreeFeller.findTree(new BukkitGrid(world),
                new TreeFeller.Pos(origin.getX(), origin.getY(), origin.getZ()), limit, minLeaves);
        if (tree.isEmpty()) {
            return;
        }
        processing.add(player.getUniqueId());
        try {
            for (TreeFeller.Pos pos : tree) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!axes.contains(hand.getType()) || nearlyBroken(hand)) {
                    break;
                }
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                BlockBreakEvent check = new BlockBreakEvent(block, player);
                Bukkit.getPluginManager().callEvent(check);
                if (check.isCancelled()) {
                    continue;
                }
                block.breakNaturally(hand, true);
                player.damageItemStack(EquipmentSlot.HAND, 1);
            }
        } finally {
            processing.remove(player.getUniqueId());
        }
    }

    private static boolean nearlyBroken(ItemStack tool) {
        if (!(tool.getItemMeta() instanceof Damageable d)) {
            return false;
        }
        int max = tool.getType().getMaxDurability();
        return max > 0 && max - d.getDamage() <= 2;
    }

    /** Adapter świata Bukkit do {@link TreeFeller.Grid}. */
    private record BukkitGrid(World world) implements TreeFeller.Grid {
        @Override
        public boolean isLog(int x, int y, int z) {
            return Tag.LOGS.isTagged(world.getBlockAt(x, y, z).getType());
        }

        @Override
        public boolean isNaturalLeaves(int x, int y, int z) {
            Block b = world.getBlockAt(x, y, z);
            return Tag.LEAVES.isTagged(b.getType()) && b.getBlockData() instanceof Leaves l && !l.isPersistent();
        }
    }
}
