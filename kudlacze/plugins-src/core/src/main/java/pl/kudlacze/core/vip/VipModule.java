package pl.kudlacze.core.vip;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.TimeFormat;
import pl.kudlacze.core.world.SpawnLayout;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Strefa VIP przy spawnie: wstęp tylko z {@code kudlacze.strefavip}, Skarbnik z codzienną nagrodą
 * ({@code /skarbnik}, NPC wywołuje komendę) oraz expiarka — kontrolowany spawner zombie w dole,
 * aktywny tylko przy graczu z {@code kudlacze.expiarka}; moby dają wyłącznie doświadczenie.
 */
public final class VipModule implements CoreModule, Listener {

    public static final String PERM_ZONE = "kudlacze.strefavip";
    public static final String PERM_EXPIARKA = "kudlacze.expiarka";
    public static final String PERM_REWARD = "kudlacze.nagroda.codzienna";
    public static final String COOLDOWN_KEY = "nagroda-dzienna";

    private final CoreContext ctx;
    private final Random random = new Random();
    private final Map<UUID, Long> lastWarn = new HashMap<>();
    private NamespacedKey mobKey;
    private DailyReward rewards;
    private Cooldowns cooldowns;
    private BukkitTask expiarkaTask;

    public VipModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "strefa-vip";
    }

    @Override
    public void enable() {
        mobKey = new NamespacedKey(ctx.plugin, "expiarka");
        rewards = DailyReward.fromSection(ctx.config.raw(), "nagroda-codzienna.nagrody");
        cooldowns = new Cooldowns(ctx.sql);
        KudlaczeCore.command(ctx, "skarbnik", new SkarbnikCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        long period = ctx.config.integer("expiarka.co-ile-sekund", 4) * 20L;
        expiarkaTask = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tickExpiarka, period, period);
    }

    @Override
    public void disable() {
        if (expiarkaTask != null) {
            expiarkaTask.cancel();
        }
        SpawnLayout l = layout();
        if (l != null) {
            l.world().getEntities().stream().filter(this::isExpiarkaMob).forEach(Entity::remove);
        }
    }

    private SpawnLayout layout() {
        return ctx.service(SpawnLayout.class);
    }

    // ---- wstęp do strefy -------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        guard(event.getPlayer(), event.getFrom(), event.getTo(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        guard(event.getPlayer(), event.getFrom(), event.getTo(), () -> event.setCancelled(true));
    }

    private void guard(Player player, Location from, Location to, Runnable cancel) {
        SpawnLayout l = layout();
        if (l == null || to == null || player.hasPermission(PERM_ZONE)) {
            return;
        }
        if (l.insideVip(to) && !l.insideVip(from)) {
            cancel.run();
            long now = System.currentTimeMillis();
            if (now - lastWarn.getOrDefault(player.getUniqueId(), 0L) > 3000) {
                lastWarn.put(player.getUniqueId(), now);
                ctx.messages.send(player, "vip.brak-wstepu");
            }
        }
    }

    // ---- Skarbnik -------------------------------------------------------------------

    private final class SkarbnikCommand extends BaseCommand {
        SkarbnikCommand() {
            super(VipModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p == null) {
                return;
            }
            if (!p.hasPermission(PERM_REWARD)) {
                msg.send(p, "vip.skarbnik-tylko-vip");
                return;
            }
            Instant now = Instant.now(ctx.clock);
            long until = DailyReward.nextReset(now, ctx.config.zone()).toEpochMilli();
            UUID uuid = p.getUniqueId();
            ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> cooldowns.tryAcquire(uuid, COOLDOWN_KEY, now.toEpochMilli(), until)),
                    busy -> {
                        if (busy.isPresent()) {
                            msg.send(p, "vip.skarbnik-juz-odebrano", Messages.text("czas",
                                    TimeFormat.duration(Duration.ofMillis(busy.getAsLong() - now.toEpochMilli()))));
                            return;
                        }
                        give(p, rewards.roll(random));
                    }, e -> handleError(p, e));
        }
    }

    private void give(Player p, DailyReward.Entry reward) {
        if (reward.kc() > 0) {
            ctx.kc.give(p.getUniqueId(), reward.kc(), KcService.TxType.NAGRODA_CODZIENNA, "skarbnik");
        }
        if (reward.money() > 0) {
            ctx.money.deposit(p, reward.money());
        }
        for (String spec : reward.items()) {
            String[] parts = spec.split(":");
            Material m = Material.matchMaterial(parts[0]);
            if (m == null) {
                continue;
            }
            int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            p.getInventory().addItem(new ItemStack(m, amount)).values()
                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        ctx.messages.send(p, "vip.skarbnik-nagroda", Messages.parsed("nagroda", reward.description()));
    }

    // ---- expiarka -------------------------------------------------------------------

    private void tickExpiarka() {
        SpawnLayout l = layout();
        if (l == null) {
            return;
        }
        Location center = l.expiarka();
        double radius = ctx.config.decimal("expiarka.promien-gracza", 10);
        boolean vipNear = center.getWorld().getPlayers().stream()
                .anyMatch(p -> p.hasPermission(PERM_EXPIARKA) && p.getLocation().distanceSquared(center) <= radius * radius);
        List<Entity> mobs = center.getWorld().getNearbyEntities(center, 4, 4, 4).stream()
                .filter(this::isExpiarkaMob).toList();
        if (!vipNear) {
            mobs.forEach(Entity::remove);
            return;
        }
        int max = ctx.config.integer("expiarka.max-mobow", 6);
        if (mobs.size() >= max) {
            return;
        }
        Location spawn = center.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
        Zombie z = (Zombie) center.getWorld().spawnEntity(spawn, EntityType.ZOMBIE,
                org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                    Zombie zz = (Zombie) e;
                    zz.setAdult();
                    zz.setShouldBurnInDay(false);
                    zz.setCanPickupItems(false);
                    zz.setPersistent(false);
                    zz.setRemoveWhenFarAway(true);
                    zz.getEquipment().clear();
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        if (slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE) {
                            zz.getEquipment().setDropChance(slot, 0f);
                        }
                    }
                    zz.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
                });
        z.customName(ctx.messages.get("vip.expiarka-mob"));
    }

    boolean isExpiarkaMob(Entity e) {
        return e instanceof LivingEntity && e.getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (isExpiarkaMob(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(ctx.config.integer("expiarka.xp-za-moba", 5));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (isExpiarkaMob(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
