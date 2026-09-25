package pl.kudlacze.core.dragon;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.DragonBattle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.db.Sql;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.seasons.SeasonSchedule;
import pl.kudlacze.core.seasons.SeasonService;
import pl.kudlacze.core.titles.TitleService;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Smok Kudłaty (serwer seasons): co tydzień (domyślnie niedziela 20:00) w świecie {@code seasons_boss}.
 * Ogłoszenie 30 min wcześniej na czacie i Discordzie (#smok), arena otwiera się 15 min przed walką
 * ({@code /smok}), smok odradza się przez sekwencję kryształów (DragonBattle), obrażenia liczone są
 * dla graczy (także pociski/TNT), top 5 dostaje punkty sezonu 100/80/60/40/20, KC i tytuł „Smokobójca”.
 * Arena: bez niszczenia terenu (bloki postawione w walce są sprzątane), bez PvP (config), bez bramek Endu.
 */
public final class DragonModule implements CoreModule, Listener {

    public static final String PERM_ADMIN = "kudlacze.smok.admin";

    enum State { IDLE, OPEN, STARTING, SUMMONING, FIGHT }

    private final CoreContext ctx;
    private final DragonRewards table = new DragonRewards();
    private final Set<String> announced = new HashSet<>();
    private final Set<Location> placed = new HashSet<>();
    private final List<EnderCrystal> summoningCrystals = new ArrayList<>();
    private final Set<java.util.UUID> diedInArena = new HashSet<>();
    private State state = State.IDLE;
    private World arena;
    private Instant nextFight;
    private Instant startedAt;
    private Instant fightStartedAt;
    private long fightId;
    private boolean timedOut;
    private EnderDragon dragon;
    private BukkitTask ticker;
    private BukkitTask actionBar;
    private SeasonService seasons;
    private TitleService titles;

    public DragonModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "smok";
    }

    @Override
    public void enable() {
        seasons = ctx.service(SeasonService.class);
        titles = ctx.service(TitleService.class);
        arena = prepareArena();
        nextFight = SeasonSchedule.nextDragon(ctx.config, Instant.now(ctx.clock));
        KudlaczeCore.command(ctx, "smok", new DragonCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.tasks.runAsync(() -> ctx.sql.update("UPDATE smok_walki SET koniec = ? WHERE koniec IS NULL", ctx.clock.millis()));
        ticker = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tick, 40L, 20L * 5);
        actionBar = Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::showDamage, 20L * 5, 20L * 5);
        ctx.plugin.getLogger().info("Smok Kudłaty: arena '" + arena.getName() + "', następna walka " + nextFight);
    }

    @Override
    public void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (actionBar != null) {
            actionBar.cancel();
        }
        cleanupPlaced();
    }

    private World prepareArena() {
        String name = ctx.config.string("smok.swiat", "seasons_boss");
        World w = Bukkit.getWorld(name);
        if (w == null) {
            ctx.plugin.getLogger().info("Tworzę arenę smoka '" + name + "' (THE_END)…");
            w = new WorldCreator(name).environment(World.Environment.THE_END).createWorld();
        }
        if (w == null) {
            throw new IllegalStateException("Nie udało się utworzyć świata " + name);
        }
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.getWorldBorder().setCenter(0, 0);
        w.getWorldBorder().setSize(ctx.config.integer("smok.granica", 400));
        DragonBattle battle = w.getEnderDragonBattle();
        if (battle != null) {
            // W 26.2 pierwsze skanowanie walki (EnderDragonFight.scanState) uznaje smoka za pokonanego tylko,
            // gdy istnieje AKTYWNY portal wyjściowy — inaczej sama tworzy smoka przy wejściu pierwszego gracza.
            if (battle.generateEndPortal(true)) {
                ctx.plugin.getLogger().info("Arena smoka: utworzono aktywny portal wyjściowy.");
            }
            battle.setPreviouslyKilled(true);
        }
        return w;
    }

    // ---- harmonogram ----------------------------------------------------------------

    private void tick() {
        Instant now = Instant.now(ctx.clock);
        long minutes = Duration.between(now, nextFight).toMinutes();
        String fightKey = nextFight.toString();
        int announceMin = ctx.config.integer("smok.ogloszenie-minut-przed", 30);
        int openMin = ctx.config.integer("smok.arena-otwarta-minut-przed", 15);
        if (state == State.IDLE) {
            if (minutes < announceMin && minutes >= announceMin - 1 && announced.add(fightKey + ":zapowiedz")) {
                Bukkit.broadcast(ctx.messages.get("smok.zapowiedz", Messages.text("minuty", announceMin)));
                ctx.discord.announce("smok", ctx.messages.raw("smok.discord-zapowiedz")
                        .replace("<minuty>", String.valueOf(announceMin)));
            }
            killStrayDragons();
            if (minutes < openMin) {
                open();
            }
        }
        if (state == State.OPEN) {
            killStrayDragons();
            for (int warn : List.of(5, 1)) {
                if (minutes < warn && minutes >= warn - 1 && announced.add(fightKey + ":" + warn)) {
                    Bukkit.broadcast(ctx.messages.get("smok.za-chwile", Messages.text("minuty", warn)));
                }
            }
            if (!now.isBefore(nextFight)) {
                state = State.STARTING;
                startedAt = now;
            }
        }
        if (state == State.STARTING) {
            tryStart(now);
        }
        if (state == State.SUMMONING && arena.getEnderDragonBattle() != null
                && arena.getEnderDragonBattle().getEnderDragon() != null) {
            appeared(arena.getEnderDragonBattle().getEnderDragon());
        }
        if (state == State.SUMMONING && Duration.between(startedAt, now).toMinutes() >= 5 && dragon == null) {
            ctx.plugin.getLogger().warning("Smok nie pojawił się 5 minut po rozpoczęciu sekwencji — przerywam.");
            finish(false, "smok.przerwano");
        }
        if (state == State.FIGHT) {
            if (dragon != null && !dragon.isValid() && !dragon.isDead()) {
                dragon = findDragon();
            }
            int limit = ctx.config.integer("smok.limit-minut", 30);
            if (Duration.between(fightStartedAt, now).toMinutes() >= limit) {
                timedOut = true;
                EnderDragon d = dragon != null ? dragon : findDragon();
                if (d != null && !d.isDead()) {
                    d.setHealth(0); // śmierć smoka zamyka walkę w DragonBattle (inaczej odrodziłby się sam)
                } else {
                    finish(false, "smok.uciekl");
                }
            }
        }
    }

    private void open() {
        state = State.OPEN;
        table.clear();
        placed.clear();
        Bukkit.broadcast(ctx.messages.get("smok.arena-otwarta", Messages.text("czas",
                TimeFormat.duration(Duration.between(Instant.now(ctx.clock), nextFight).plusSeconds(1)))));
    }

    private void tryStart(Instant now) {
        if (arena.getPlayers().isEmpty()) {
            if (Duration.between(startedAt, now).toMinutes() >= ctx.config.integer("smok.czekaj-na-graczy-minut", 10)) {
                Bukkit.broadcast(ctx.messages.get("smok.nikt-nie-przyszedl"));
                reset();
            }
            return;
        }
        DragonBattle battle = arena.getEnderDragonBattle();
        if (battle == null) {
            ctx.plugin.getLogger().severe("Świat areny nie ma walki ze smokiem (DragonBattle) — sprawdź środowisko THE_END");
            reset();
            return;
        }
        EnderDragon alive = battle.getEnderDragon();
        if (alive != null && alive.isValid() && !alive.isDead()) {
            // smok już lata (np. utworzony przez samą walkę) — przejmujemy go zamiast przyzywać nowego
            recordFightStart();
            Bukkit.broadcast(ctx.messages.get("smok.budzi-sie"));
            ctx.discord.announce("smok", ctx.messages.raw("smok.discord-start"));
            appeared(alive);
            return;
        }
        if (!battle.hasBeenPreviouslyKilled()) {
            battle.setPreviouslyKilled(true);
        }
        Location portal = battle.getEndPortalLocation();
        if (portal == null) {
            battle.generateEndPortal(false);
            portal = battle.getEndPortalLocation();
            if (portal == null) {
                return; // spróbujemy za 5 s (obszar areny ładuje się dopiero przy graczach)
            }
        }
        summoningCrystals.forEach(Entity::remove);
        summoningCrystals.clear();
        int[][] offsets = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};
        for (int[] o : offsets) {
            Location at = portal.clone().add(o[0] + 0.5, 1, o[1] + 0.5);
            summoningCrystals.add(arena.spawn(at, EnderCrystal.class, c -> c.setShowingBottom(false)));
        }
        if (battle.initiateRespawn(summoningCrystals)) {
            state = State.SUMMONING;
            startedAt = now;
            Bukkit.broadcast(ctx.messages.get("smok.budzi-sie"));
            ctx.discord.announce("smok", ctx.messages.raw("smok.discord-start"));
            recordFightStart();
        } else {
            summoningCrystals.forEach(Entity::remove);
            summoningCrystals.clear();
        }
    }

    private void recordFightStart() {
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> ctx.sql.transaction(c -> {
            Sql.update(c, "INSERT INTO smok_walki (sezon, start, zabity) VALUES (?, ?, FALSE)",
                    seasons.currentSeason(), ctx.clock.millis());
            return Sql.query(c, "SELECT MAX(id) FROM smok_walki", rs -> rs.getLong(1)).getFirst();
        })), id -> fightId = id, e -> ctx.plugin.getLogger().log(Level.WARNING, "Nie zapisano walki smoka", e));
    }

    private EnderDragon findDragon() {
        return arena.getEntitiesByClass(EnderDragon.class).stream().filter(Entity::isValid).findFirst().orElse(null);
    }

    /**
     * Poza walką smok nie ma prawa latać (smok z pierwszego skanowania walki, restart w trakcie walki) —
     * ginie bez nagród; jego śmierć ustawia walkę w stan „pokonany” z aktywnym portalem.
     */
    private void killStrayDragons() {
        if (arena.getPlayers().isEmpty()) {
            return;
        }
        for (EnderDragon d : arena.getEntitiesByClass(EnderDragon.class)) {
            if (!d.isDead()) {
                d.setHealth(0);
            }
        }
    }

    // ---- walka ----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon d && d.getWorld().equals(arena) && state == State.SUMMONING) {
            appeared(d);
        }
    }

    private void appeared(EnderDragon d) {
        dragon = d;
        state = State.FIGHT;
        fightStartedAt = Instant.now(ctx.clock);
        timedOut = false;
        Bukkit.getScheduler().runTask(ctx.plugin, () -> {
            double hp = ctx.config.decimal("smok.zdrowie", 400);
            AttributeInstance max = d.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && hp > 0) {
                max.setBaseValue(hp);
                d.setHealth(hp);
            }
            d.customName(ctx.messages.get("smok.nazwa"));
        });
        Bukkit.broadcast(ctx.messages.get("smok.pojawil-sie", Messages.text("minuty", ctx.config.integer("smok.limit-minut", 30))));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (state != State.FIGHT || !event.getEntity().getWorld().equals(arena)) {
            return;
        }
        Entity target = event.getEntity() instanceof ComplexEntityPart part ? part.getParent() : event.getEntity();
        if (!(target instanceof EnderDragon d)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        double dealt = Math.min(event.getFinalDamage(), Math.max(0, d.getHealth()));
        table.record(attacker.getUniqueId(), attacker.getName(), dealt);
    }

    static Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            return p;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon) || !event.getEntity().getWorld().equals(arena)) {
            return;
        }
        if (state != State.FIGHT) {
            event.setDroppedExp(0);
            event.getDrops().clear();
            return;
        }
        if (timedOut) {
            event.setDroppedExp(0);
            event.getDrops().clear();
            finish(false, "smok.uciekl");
        } else {
            finish(true, null);
        }
    }

    private void showDamage() {
        if (state != State.FIGHT) {
            return;
        }
        List<DragonRewards.Hit> ranking = table.ranking();
        for (Player p : arena.getPlayers()) {
            int place = 0;
            double dmg = 0;
            for (int i = 0; i < ranking.size(); i++) {
                if (ranking.get(i).uuid().equals(p.getUniqueId())) {
                    place = i + 1;
                    dmg = ranking.get(i).damage();
                }
            }
            p.sendActionBar(ctx.messages.get("smok.pasek", Messages.text("obrazenia", Math.round(dmg)),
                    Messages.text("miejsce", place == 0 ? "—" : String.valueOf(place)),
                    Messages.text("gracze", ranking.size())));
        }
    }

    /** Koniec walki: nagrody (gdy zabity), zapis do bazy, ogłoszenia, sprzątanie areny. */
    private void finish(boolean killed, String reasonKey) {
        State was = state;
        List<DragonRewards.Hit> ranking = table.ranking();
        List<DragonRewards.Reward> rewards = killed ? DragonRewards.compute(ranking,
                ctx.config.raw().getLongList("smok.punkty"), ctx.config.raw().getLongList("smok.kc"),
                ctx.config.integer("smok.tytul-miejsc", 5), ctx.config.decimal("smok.min-obrazen", 10)) : List.of();
        long id = fightId;
        String title = ctx.config.string("smok.tytul", "smokobojca");
        long now = ctx.clock.millis();
        if (was == State.FIGHT || was == State.SUMMONING) {
            ctx.tasks.runAsync(() -> {
                if (id > 0) {
                    ctx.sql.update("UPDATE smok_walki SET koniec = ?, zabity = ? WHERE id = ?", now, killed, id);
                    for (DragonRewards.Hit h : ranking) {
                        ctx.sql.update("INSERT INTO smok_obrazenia (walka, uuid, nick, obrazenia) VALUES (?, ?, ?, ?)",
                                id, h.uuid(), h.name(), h.damage());
                    }
                }
                for (DragonRewards.Reward r : rewards) {
                    seasons.addPoints(r.uuid(), r.name(), r.points(), 0);
                    if (r.kc() > 0) {
                        ctx.kc.give(r.uuid(), r.kc(), KcService.TxType.SMOK, "smok miejsce " + r.place()).join();
                    }
                    if (r.title() && titles != null) {
                        titles.grant(r.uuid(), title, "Smok Kudłaty", now);
                    }
                }
            });
        }
        if (killed) {
            Bukkit.broadcast(ctx.messages.get("smok.pokonany"));
            StringBuilder discord = new StringBuilder(ctx.messages.raw("smok.discord-pokonany"));
            for (DragonRewards.Reward r : rewards) {
                Bukkit.broadcast(ctx.messages.get("smok.wynik", Messages.text("miejsce", r.place()),
                        Messages.text("gracz", r.name()), Messages.text("obrazenia", Math.round(r.damage())),
                        Messages.text("punkty", r.points()), Messages.text("kc", r.kc())));
                discord.append("\n").append(r.place()).append(". ").append(r.name()).append(" — ")
                        .append(Math.round(r.damage())).append(" obrażeń, +").append(r.points()).append(" pkt, +")
                        .append(r.kc()).append(" KC");
            }
            ctx.discord.announce("smok", discord.toString());
        } else if (reasonKey != null) {
            Bukkit.broadcast(ctx.messages.get(reasonKey));
            ctx.discord.announce("smok", ctx.messages.raw("smok.discord-" + reasonKey.substring(reasonKey.indexOf('.') + 1)));
        }
        reset();
        Bukkit.getScheduler().runTaskLater(ctx.plugin, this::cleanupPlaced, 20L * 60);
    }

    private void reset() {
        state = State.IDLE;
        dragon = null;
        fightId = 0;
        timedOut = false;
        summoningCrystals.forEach(Entity::remove);
        summoningCrystals.clear();
        nextFight = SeasonSchedule.nextDragon(ctx.config, Instant.now(ctx.clock).plusSeconds(60));
    }

    private void cleanupPlaced() {
        for (Location l : placed) {
            if (l.getWorld() != null && l.isChunkLoaded()) {
                l.getBlock().setType(Material.AIR, false);
            }
        }
        placed.clear();
    }

    // ---- ochrona areny --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEgg(io.papermc.paper.event.block.DragonEggFormEvent event) {
        if (event.getBlock().getWorld().equals(arena)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (event.getPlayer().getWorld().equals(arena) && state != State.IDLE) {
            diedInArena.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (diedInArena.remove(event.getPlayer().getUniqueId()) && state != State.IDLE) {
            event.setRespawnLocation(arenaPoint());
        }
    }

    private boolean protectedHere(Entity who, World world) {
        return world.equals(arena) && !(who instanceof Player p && p.hasPermission(PERM_ADMIN));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!protectedHere(event.getPlayer(), event.getBlock().getWorld())) {
            return;
        }
        if (state == State.IDLE) {
            event.setCancelled(true);
            return;
        }
        placed.add(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!protectedHere(event.getPlayer(), b.getWorld())) {
            return;
        }
        if (placed.remove(b.getLocation())) {
            return;
        }
        if (b.getType() == Material.IRON_BARS && state == State.FIGHT) {
            event.setDropItems(false); // klatki kryształów — odbudowują się przy następnym przyzwaniu
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (protectedHere(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrystalPlace(EntityPlaceEvent event) {
        if (event.getEntityType() == EntityType.END_CRYSTAL && protectedHere(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity().getWorld().equals(arena)) {
            event.blockList().removeIf(b -> !placed.remove(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.getBlock().getWorld().equals(arena)) {
            event.blockList().removeIf(b -> !placed.remove(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (ctx.config.bool("smok.pvp", false) || !(event.getEntity() instanceof Player)
                || !event.getEntity().getWorld().equals(arena)) {
            return;
        }
        if (attacker(event.getDamager()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGateway(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_GATEWAY && event.getFrom().getWorld().equals(arena)) {
            event.setCancelled(true);
        }
    }

    // ---- komenda --------------------------------------------------------------------

    Location arenaPoint() {
        double x = ctx.config.decimal("smok.arena.x", 0.5);
        double z = ctx.config.decimal("smok.arena.z", 60.5);
        int y = arena.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        if (y <= arena.getMinHeight() + 1) {
            y = 64;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    arena.getBlockAt((int) Math.floor(x) + dx, y, (int) Math.floor(z) + dz).setType(Material.OBSIDIAN);
                }
            }
        }
        Location l = new Location(arena, x, y + 1, z);
        l.setYaw(180f); // twarzą w stronę środka wyspy
        return l;
    }

    private final class DragonCommand extends BaseCommand {
        DragonCommand() {
            super(DragonModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("start") || sub.equals("stop")) {
                if (!requirePermission(sender, PERM_ADMIN)) {
                    return;
                }
                if (sub.equals("start")) {
                    if (state != State.IDLE && state != State.OPEN) {
                        msg.send(sender, "smok.juz-trwa");
                        return;
                    }
                    nextFight = Instant.now(ctx.clock);
                    if (state == State.IDLE) {
                        open();
                    }
                    state = State.STARTING;
                    startedAt = Instant.now(ctx.clock);
                    msg.send(sender, "smok.admin-start");
                } else {
                    timedOut = true;
                    EnderDragon d = dragon != null ? dragon : findDragon();
                    if (d != null && !d.isDead()) {
                        d.setHealth(0);
                    } else {
                        finish(false, "smok.przerwano");
                    }
                    msg.send(sender, "smok.admin-stop");
                }
                return;
            }
            if (!(sender instanceof Player p)) {
                msg.send(sender, "smok.info", Messages.text("czas", timeLeft()), Messages.text("stan", state.name()));
                return;
            }
            if (state == State.IDLE) {
                msg.send(p, "smok.info", Messages.text("czas", timeLeft()), Messages.text("stan", state.name()));
                return;
            }
            p.teleportAsync(arenaPoint(), PlayerTeleportEvent.TeleportCause.COMMAND)
                    .thenAccept(ok -> {
                        if (ok) {
                            ctx.tasks.runSync(() -> msg.send(p, "smok.teleport"));
                        }
                    });
        }

        private String timeLeft() {
            return TimeFormat.duration(Duration.between(Instant.now(ctx.clock), nextFight));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return args.length == 1 && sender.hasPermission(PERM_ADMIN) ? List.of("start", "stop") : List.of();
        }
    }

    /** Tylko testy. */
    DragonRewards table() {
        return table;
    }
}
