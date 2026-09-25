package pl.kudlacze.core.seasons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.afk.AfkStatus;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.menu.Menu;
import pl.kudlacze.core.menu.MenuService;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.titles.TitleService;
import pl.kudlacze.core.util.ConfirmCodes;
import pl.kudlacze.core.util.Schedules;
import pl.kudlacze.core.util.TimeFormat;
import pl.kudlacze.core.world.ResetRestart;
import pl.kudlacze.core.world.WorldReset;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Seasons (serwer seasons): questy u Kwatermistrza ({@code /questy}), punkty za czas gry (bez AFK),
 * wyzwania → kryształy, sklep kryształów, {@code /sezon} oraz automatyczny reset 1. dnia miesiąca
 * o 12:00 (Europe/Warsaw): archiwum rankingu, nagrody top 3 (KC + tytuł), ogłoszenie na czacie
 * i Discordzie, nowy świat.
 */
public final class SeasonsModule implements CoreModule, Listener {

    public static final String PERM_ADMIN = "kudlacze.sezon.admin";

    private final CoreContext ctx;
    private final Map<UUID, Map<String, Long>> pendingChallenges = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Set<Long> announced = new HashSet<>();
    private final ConfirmCodes codes = new ConfirmCodes(60_000);
    private final List<BukkitTask> timers = new ArrayList<>();
    private SeasonService seasons;
    private TitleService titles;
    private volatile QuestBook book;
    private Instant nextReset;
    private volatile boolean resetting;

    public SeasonsModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "sezony";
    }

    @Override
    public void enable() {
        seasons = ctx.service(SeasonService.class);
        titles = ctx.service(TitleService.class);
        book = loadBook();
        KudlaczeCore.command(ctx, "questy", new QuestsCommand());
        KudlaczeCore.command(ctx, "sezon", new SeasonCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        Bukkit.getOnlinePlayers().forEach(p -> lastActivity.put(p.getUniqueId(), System.currentTimeMillis()));

        nextReset = SeasonSchedule.nextReset(ctx.config, Instant.now(ctx.clock));
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
            long start = seasons.seasonStart(-1);
            if (start < 0) {
                ctx.kv.put(SeasonService.KEY_START, String.valueOf(ctx.clock.millis()));
                if (ctx.kv.get(SeasonService.KEY_SEASON).isEmpty()) {
                    ctx.kv.put(SeasonService.KEY_SEASON, "1");
                }
                return false;
            }
            return start < SeasonSchedule.previousReset(ctx.config, Instant.now(ctx.clock)).toEpochMilli();
        }), overdue -> {
            if (overdue) {
                ctx.plugin.getLogger().warning("Zaległy reset sezonu (serwer był wyłączony w chwili resetu) — za minutę.");
                nextReset = Instant.now(ctx.clock).plusSeconds(60);
            }
        }, e -> ctx.plugin.getLogger().log(Level.SEVERE, "Nie udało się odczytać stanu sezonu", e));

        timers.add(Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tickPlaytime, 20L * 60, 20L * 60));
        timers.add(Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> ctx.tasks.runAsync(this::flushChallenges),
                20L * 30, 20L * 30));
        timers.add(Bukkit.getScheduler().runTaskTimer(ctx.plugin, this::tickSchedule, 20L * 5, 20L * 20));
    }

    @Override
    public void disable() {
        timers.forEach(BukkitTask::cancel);
        timers.clear();
        flushChallenges();
    }

    QuestBook loadBook() {
        // questy.yml w folderze pluginu (opcjonalny) zastępuje wersję z jara w całości
        File file = new File(ctx.plugin.getDataFolder(), "questy.yml");
        YamlConfiguration yml = file.exists() ? YamlConfiguration.loadConfiguration(file)
                : YamlConfiguration.loadConfiguration(new InputStreamReader(ctx.plugin.getResource("questy.yml"),
                StandardCharsets.UTF_8));
        return QuestBook.load(yml);
    }

    // ---- aktywność / AFK -------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedOrientation() || event.hasChangedBlock()) {
            lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    private boolean isAfk(Player p) {
        AfkStatus afk = ctx.service(AfkStatus.class);
        if (afk != null) {
            return afk.isAfk(p);
        }
        long last = lastActivity.getOrDefault(p.getUniqueId(), 0L);
        return System.currentTimeMillis() - last > ctx.config.integer("sezon.afk-po-minutach", 5) * 60_000L;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }

    // ---- czas gry --------------------------------------------------------------------

    private void tickPlaytime() {
        if (resetting) {
            return;
        }
        QuestBook b = book;
        long everySeconds = b.playtimeEveryMinutes() * 60L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAfk(p)) {
                continue;
            }
            UUID uuid = p.getUniqueId();
            String name = p.getName();
            addChallengeProgress(p, QuestBook.ChallengeType.CZAS_GRY, null, 1);
            ctx.tasks.runAsync(() -> {
                long total = seasons.addPlaytime(uuid, name, 60);
                long periods = total / everySeconds - (total - 60) / everySeconds;
                if (periods > 0 && b.playtimePoints() > 0) {
                    seasons.addPoints(uuid, name, periods * b.playtimePoints(), 0);
                }
            });
        }
    }

    // ---- wyzwania --------------------------------------------------------------------

    private void addChallengeProgress(Player p, QuestBook.ChallengeType type, String target, long delta) {
        for (QuestBook.Challenge c : book.challengesOf(type)) {
            if (target == null || c.matches(target)) {
                buffer(p, c.id(), delta);
            }
        }
    }

    /** Atomowo względem opróżniania bufora w {@link #flushChallenges()} (compute/remove na tym samym kluczu). */
    private void buffer(Player p, String challengeId, long delta) {
        names.put(p.getUniqueId(), p.getName());
        pendingChallenges.compute(p.getUniqueId(), (k, map) -> {
            Map<String, Long> m = map != null ? map : new HashMap<>();
            m.merge(challengeId, delta, Long::sum);
            return m;
        });
    }

    /** Zapis buforowanego postępu wyzwań (wątek bazy). Ukończenie → punkty, kryształy i ogłoszenie. */
    void flushChallenges() {
        QuestBook b = book;
        for (UUID uuid : new ArrayList<>(pendingChallenges.keySet())) {
            Map<String, Long> deltas = pendingChallenges.remove(uuid);
            if (deltas == null) {
                continue;
            }
            String name = names.getOrDefault(uuid, "?");
            for (Map.Entry<String, Long> e : deltas.entrySet()) {
                QuestBook.Challenge c = b.challenges().get(e.getKey());
                if (c == null) {
                    continue;
                }
                try {
                    boolean done = seasons.progressChallenge(uuid, c.id(), e.getValue(), c.goal(), ctx.clock.millis());
                    if (done) {
                        seasons.addPoints(uuid, name, c.points(), c.crystals());
                        ctx.tasks.runSync(() -> announceChallenge(uuid, name, c));
                    }
                } catch (RuntimeException ex) {
                    ctx.plugin.getLogger().log(Level.WARNING, "Nie zapisano postępu wyzwania " + c.id(), ex);
                }
            }
        }
    }

    private void announceChallenge(UUID uuid, String name, QuestBook.Challenge c) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            ctx.messages.send(p, "sezon.wyzwanie-ukonczone", Messages.parsed("wyzwanie", c.name()),
                    Messages.text("punkty", c.points()), Messages.text("krysztaly", c.crystals()));
        }
        Bukkit.broadcast(ctx.messages.get("sezon.wyzwanie-ogloszenie", Messages.text("gracz", name),
                Messages.parsed("wyzwanie", c.name())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            return;
        }
        addChallengeProgress(event.getPlayer(), QuestBook.ChallengeType.KOPANIE, event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || dead.fromMobSpawner()) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = dead.getEntitySpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG || reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) {
            return;
        }
        for (QuestBook.Challenge c : book.challengesOf(QuestBook.ChallengeType.ZABIJANIE)) {
            boolean match = c.targets().isEmpty() || c.targets().contains(dead.getType().name())
                    || c.targets().contains("WROGIE") && dead instanceof Enemy;
            if (match) {
                buffer(killer, c.id(), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            addChallengeProgress(event.getPlayer(), QuestBook.ChallengeType.LOWIENIE, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player p) {
            addChallengeProgress(p, QuestBook.ChallengeType.ROZMNAZANIE, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        addChallengeProgress(event.getEnchanter(), QuestBook.ChallengeType.ZACZAROWANIE, null, 1);
    }

    // ---- questy (Kwatermistrz) --------------------------------------------------------

    void openQuests(Player player) {
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now(ctx.clock);
        long day = Schedules.dayStart(now, ctx.config.zone()).toEpochMilli();
        long week = Schedules.weekStart(now, ctx.config.zone()).toEpochMilli();
        QuestBook b = book;
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new Object[]{
                seasons.entry(uuid), seasons.place(uuid), seasons.questCountsSince(uuid, day),
                seasons.questKcSince(uuid, week), seasons.currentSeason()}), data -> {
            @SuppressWarnings("unchecked")
            var entry = (java.util.Optional<SeasonService.Entry>) data[0];
            @SuppressWarnings("unchecked")
            var place = (java.util.Optional<Integer>) data[1];
            @SuppressWarnings("unchecked")
            var today = (Map<String, Integer>) data[2];
            long weekKc = (Long) data[3];
            int season = (Integer) data[4];
            Messages m = ctx.messages;
            long points = entry.map(SeasonService.Entry::points).orElse(0L);
            long crystals = entry.map(SeasonService.Entry::crystals).orElse(0L);
            String placeText = place.map(String::valueOf).orElse("—");
            Menu.Builder menu = Menu.builder(m.get("sezon.menu-tytul", Messages.text("sezon", season)), 6)
                    .content(m.raw("sezon.menu-bedrock").replace("<punkty>", String.valueOf(points))
                            .replace("<miejsce>", placeText).replace("<kc>", String.valueOf(weekKc))
                            .replace("<limit>", String.valueOf(b.weeklyKcLimit())))
                    .filler(MenuService.icon(Material.GREEN_STAINED_GLASS_PANE, Component.space(), List.of()));
            menu.button(4, MenuService.icon(Material.NETHER_STAR, m.get("sezon.menu-info", Messages.text("sezon", season)),
                            m.getList("sezon.menu-info-opis", Messages.text("punkty", points), Messages.text("miejsce", placeText),
                                    Messages.text("krysztaly", crystals), Messages.text("kc", weekKc),
                                    Messages.text("limit", b.weeklyKcLimit()),
                                    Messages.text("czas", TimeFormat.duration(Duration.between(Instant.now(ctx.clock), nextReset))))),
                    "Sezon " + season + ": " + points + " pkt, miejsce " + placeText, Player::closeInventory);
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
            int i = 0;
            for (QuestBook.Quest q : b.quests().values()) {
                if (i >= slots.length) {
                    break;
                }
                int done = today.getOrDefault(q.id(), 0);
                List<Component> lore = new ArrayList<>();
                lore.add(m.get("sezon.quest-wymaga", Messages.text("ilosc", q.amount()),
                        Messages.text("przedmiot", itemNames(q))));
                lore.add(m.get("sezon.quest-nagroda", Messages.text("punkty", q.points()), Messages.text("kc", q.kc())));
                if (q.dailyLimit() > 0) {
                    lore.add(m.get("sezon.quest-limit", Messages.text("zrobione", done), Messages.text("limit", q.dailyLimit())));
                }
                lore.add(Component.empty());
                lore.add(m.get(q.dailyLimit() > 0 && done >= q.dailyLimit() ? "sezon.quest-jutro" : "sezon.quest-oddaj"));
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(m.parse(q.name()));
                menu.button(slots[i++], MenuService.icon(q.icon(), m.parse(q.name()), lore),
                        plain + " (" + q.amount() + " szt. → " + q.points() + " pkt)", p -> {
                            p.closeInventory();
                            deliver(p, q);
                        });
            }
            menu.button(47, MenuService.icon(Material.WRITABLE_BOOK, m.get("sezon.menu-wyzwania"), m.getList("sezon.menu-wyzwania-opis")),
                    "Wyzwania sezonu", this::openChallenges);
            menu.button(51, MenuService.icon(Material.AMETHYST_CLUSTER, m.get("sezon.menu-sklep",
                            Messages.text("krysztaly", crystals)), m.getList("sezon.menu-sklep-opis")),
                    "Sklep kryształów (" + crystals + ")", this::openShop);
            ctx.menus.open(player, menu.build());
        }, e -> {
            ctx.plugin.getLogger().log(Level.SEVERE, "Menu questów", e);
            ctx.messages.send(player, "blad-wewnetrzny");
        });
    }

    private static String itemNames(QuestBook.Quest q) {
        if (q.items().size() > 1) {
            return q.items().getFirst().name().toLowerCase(Locale.ROOT) + " (lub podobne)";
        }
        return q.items().getFirst().name().toLowerCase(Locale.ROOT);
    }

    private static int count(PlayerInventory inv, QuestBook.Quest q) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it != null && q.accepts(it.getType())) {
                n += it.getAmount();
            }
        }
        return n;
    }

    /** Zabiera przedmioty questu; zwraca zabrane stosy (do zwrotu przy błędzie zapisu). */
    private static List<ItemStack> remove(PlayerInventory inv, QuestBook.Quest q, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = inv.getStorageContents();
        int left = amount;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack it = contents[slot];
            if (it == null || !q.accepts(it.getType())) {
                continue;
            }
            int take = Math.min(left, it.getAmount());
            left -= take;
            taken.add(it.asQuantity(take));
            if (take == it.getAmount()) {
                inv.setItem(slot, null);
            } else {
                it.setAmount(it.getAmount() - take);
                inv.setItem(slot, it);
            }
        }
        return taken;
    }

    /** Oddanie questu: limit dzienny i tygodniowy limit KC sprawdzane w bazie, przedmioty zabierane na wątku głównym. */
    void deliver(Player p, QuestBook.Quest q) {
        if (count(p.getInventory(), q) < q.amount()) {
            ctx.messages.send(p, "sezon.quest-brak", Messages.text("ilosc", q.amount()),
                    Messages.text("przedmiot", itemNames(q)));
            return;
        }
        UUID uuid = p.getUniqueId();
        if (!busy.add(uuid)) {
            return;
        }
        Instant now = Instant.now(ctx.clock);
        long day = Schedules.dayStart(now, ctx.config.zone()).toEpochMilli();
        long week = Schedules.weekStart(now, ctx.config.zone()).toEpochMilli();
        long limit = book.weeklyKcLimit();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new long[]{
                seasons.questCountSince(uuid, q.id(), day), seasons.questKcSince(uuid, week)}), counts -> {
            if (q.dailyLimit() > 0 && counts[0] >= q.dailyLimit()) {
                busy.remove(uuid);
                ctx.messages.send(p, "sezon.quest-limit-dzienny", Messages.text("limit", q.dailyLimit()));
                return;
            }
            if (!p.isOnline() || count(p.getInventory(), q) < q.amount()) {
                busy.remove(uuid);
                return;
            }
            List<ItemStack> taken = remove(p.getInventory(), q, q.amount());
            long kc = QuestBook.cappedKc(q.kc(), counts[1], limit);
            String name = p.getName();
            CompletableFuture<Void> write = ctx.tasks.runAsync(() -> {
                seasons.logQuest(uuid, q.id(), q.amount(), q.points(), kc, ctx.clock.millis());
                seasons.addPoints(uuid, name, q.points(), 0);
            });
            if (kc > 0) {
                write = write.thenCompose(v -> ctx.kc.give(uuid, kc, KcService.TxType.QUEST, "quest " + q.id()).thenApply(x -> null));
            }
            ctx.tasks.thenSync(write, v -> {
                busy.remove(uuid);
                if (kc > 0) {
                    ctx.messages.send(p, "sezon.quest-wykonany", Messages.parsed("quest", q.name()),
                            Messages.text("punkty", q.points()), Messages.text("kc", kc));
                } else {
                    ctx.messages.send(p, "sezon.quest-wykonany-bez-kc", Messages.parsed("quest", q.name()),
                            Messages.text("punkty", q.points()), Messages.text("limit", limit));
                }
            }, e -> {
                busy.remove(uuid);
                ctx.plugin.getLogger().log(Level.SEVERE, "Błąd zapisu questu " + q.id() + " gracza " + name, e);
                if (p.isOnline()) {
                    p.getInventory().addItem(taken.toArray(ItemStack[]::new)).values()
                            .forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                }
                ctx.messages.send(p, "blad-wewnetrzny");
            });
        }, e -> {
            busy.remove(uuid);
            ctx.messages.send(p, "blad-wewnetrzny");
        });
    }

    private void openChallenges(Player player) {
        UUID uuid = player.getUniqueId();
        QuestBook b = book;
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> seasons.challengeStates(uuid)), states -> {
            Messages m = ctx.messages;
            Menu.Builder menu = Menu.builder(m.get("sezon.wyzwania-tytul"), 3).content(m.raw("sezon.wyzwania-bedrock"));
            int slot = 0;
            for (QuestBook.Challenge c : b.challenges().values()) {
                if (slot >= 26) {
                    break;
                }
                long[] st = states.getOrDefault(c.id(), new long[]{0, 0});
                long progress = st[0] + pendingOf(uuid, c.id());
                boolean done = st[1] == 1;
                List<Component> lore = new ArrayList<>();
                lore.add(m.parse("<gray>" + c.description()));
                lore.add(m.get("sezon.wyzwanie-nagroda", Messages.text("krysztaly", c.crystals()), Messages.text("punkty", c.points())));
                lore.add(done ? m.get("sezon.wyzwanie-zrobione")
                        : m.get("sezon.wyzwanie-postep", Messages.text("postep", Math.min(progress, c.goal())), Messages.text("cel", c.goal())));
                menu.button(slot++, MenuService.icon(done ? Material.LIME_DYE : c.icon(), m.parse(c.name()), lore),
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m.parse(c.name()))
                                + (done ? " ✔" : " " + Math.min(progress, c.goal()) + "/" + c.goal()), this::openQuests);
            }
            menu.button(26, MenuService.icon(Material.ARROW, m.get("sezon.wroc"), List.of()), "Wróć", this::openQuests);
            ctx.menus.open(player, menu.build());
        }, e -> ctx.messages.send(player, "blad-wewnetrzny"));
    }

    private void openShop(Player player) {
        UUID uuid = player.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> seasons.entry(uuid).map(SeasonService.Entry::crystals).orElse(0L)), crystals -> {
            Messages m = ctx.messages;
            Menu.Builder menu = Menu.builder(m.get("sezon.sklep-tytul", Messages.text("krysztaly", crystals)), 3)
                    .content(m.raw("sezon.sklep-bedrock").replace("<krysztaly>", String.valueOf(crystals)));
            int slot = 10;
            for (QuestBook.ShopItem item : book.shop().values()) {
                if (slot > 16) {
                    break;
                }
                List<Component> lore = List.of(m.get("sezon.sklep-cena", Messages.text("cena", item.price())),
                        m.get(crystals >= item.price() ? "sezon.sklep-kup" : "sezon.sklep-za-malo"));
                menu.button(slot++, MenuService.icon(item.material(), m.parse(item.name() + " <gray>x" + item.amount()), lore),
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m.parse(item.name()))
                                + " x" + item.amount() + " — " + item.price() + " kryształów", p -> {
                            p.closeInventory();
                            buy(p, item);
                        });
            }
            menu.button(22, MenuService.icon(Material.ARROW, m.get("sezon.wroc"), List.of()), "Wróć", this::openQuests);
            ctx.menus.open(player, menu.build());
        }, e -> ctx.messages.send(player, "blad-wewnetrzny"));
    }

    private void buy(Player p, QuestBook.ShopItem item) {
        UUID uuid = p.getUniqueId();
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> seasons.spendCrystals(uuid, item.price())), ok -> {
            if (!ok) {
                ctx.messages.send(p, "sezon.sklep-brak-krysztalow");
                return;
            }
            p.getInventory().addItem(new ItemStack(item.material(), item.amount())).values()
                    .forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
            ctx.messages.send(p, "sezon.sklep-kupiono", Messages.parsed("przedmiot", item.name()),
                    Messages.text("cena", item.price()));
        }, e -> ctx.messages.send(p, "blad-wewnetrzny"));
    }

    // ---- harmonogram i reset ---------------------------------------------------------

    private void tickSchedule() {
        if (resetting || nextReset == null) {
            return;
        }
        Instant now = Instant.now(ctx.clock);
        long minutesLeft = Duration.between(now, nextReset).toMinutes();
        for (int warn : ctx.config.raw().getIntegerList("sezon.ostrzezenia-minut")) {
            if (minutesLeft < warn && minutesLeft >= warn - 1 && announced.add((long) warn)) {
                Bukkit.broadcast(ctx.messages.get("sezon.koniec-za", Messages.text("czas",
                        TimeFormat.duration(Duration.ofMinutes(warn)))));
            }
        }
        if (!now.isBefore(nextReset)) {
            endSeason("harmonogram");
        }
    }

    /** Koniec sezonu: archiwum + nagrody w bazie, ogłoszenia, znacznik resetu świata i restart serwera. */
    void endSeason(String reason) {
        if (resetting) {
            return;
        }
        resetting = true;
        ctx.plugin.getLogger().warning("Koniec sezonu (" + reason + ")");
        Bukkit.broadcast(ctx.messages.get("sezon.koniec-trwa"));
        List<Long> kcRewards = ctx.config.raw().getLongList("sezon.nagrody-kc");
        List<String> titleIds = ctx.config.list("sezon.tytuly");
        int archiveSize = ctx.config.integer("sezon.archiwum-miejsc", 10);
        ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> {
            flushChallenges();
            int season = seasons.currentSeason();
            long now = ctx.clock.millis();
            List<SeasonService.Archived> top = seasons.closeSeason(season, archiveSize, now);
            for (SeasonService.Archived a : top) {
                int i = a.place() - 1;
                if (i < kcRewards.size() && kcRewards.get(i) > 0) {
                    ctx.kc.give(a.uuid(), kcRewards.get(i), KcService.TxType.RANKING_SEZONU,
                            "sezon " + season + " miejsce " + a.place()).join();
                }
                if (i < titleIds.size() && titles != null) {
                    titles.grant(a.uuid(), titleIds.get(i), "Sezon " + season, now);
                }
            }
            return Map.entry(season, top);
        }), result -> {
            int season = result.getKey();
            List<SeasonService.Archived> top = result.getValue();
            announceResults(season, top, kcRewards);
            ResetRestart.restart(ctx, new WorldReset.Plan("sezon", season, ctx.config.bool("sezon.archiwizuj-swiat", false),
                            true, ctx.config.list("sezon.dodatkowe-swiaty")),
                    ctx.messages.get("sezon.kick-reset", Messages.text("sezon", season + 1)));
        }, e -> {
            resetting = false;
            ctx.plugin.getLogger().log(Level.SEVERE, "Reset sezonu nie powiódł się — spróbuj /sezon reset", e);
            Bukkit.broadcast(ctx.messages.get("blad-wewnetrzny"));
        });
    }

    private void announceResults(int season, List<SeasonService.Archived> top, List<Long> kcRewards) {
        Messages m = ctx.messages;
        Bukkit.broadcast(m.get("sezon.wyniki-naglowek", Messages.text("sezon", season)));
        StringBuilder discord = new StringBuilder(m.raw("sezon.discord-naglowek").replace("<sezon>", String.valueOf(season)));
        if (top.isEmpty()) {
            Bukkit.broadcast(m.get("sezon.wyniki-brak"));
        }
        for (SeasonService.Archived a : top) {
            long kc = a.place() - 1 < kcRewards.size() ? kcRewards.get(a.place() - 1) : 0;
            if (a.place() <= 3) {
                Bukkit.broadcast(m.get("sezon.wyniki-podium", Messages.text("miejsce", a.place()),
                        Messages.text("gracz", a.name()), Messages.text("punkty", a.points()), Messages.text("kc", kc)));
            }
            discord.append("\n").append(a.place()).append(". ").append(a.name()).append(" — ").append(a.points()).append(" pkt");
            if (kc > 0) {
                discord.append(" (+").append(kc).append(" KC)");
            }
        }
        ctx.discord.announce("ranking", discord.toString());
    }

    // ---- komendy ---------------------------------------------------------------------

    private final class QuestsCommand extends BaseCommand {
        QuestsCommand() {
            super(SeasonsModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p != null) {
                openQuests(p);
            }
        }
    }

    private final class SeasonCommand extends BaseCommand {
        SeasonCommand() {
            super(SeasonsModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reset" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    String key = "sezon:" + sender.getName();
                    if (args.length >= 2 && codes.consume(key, args[1], System.currentTimeMillis())) {
                        endSeason("komenda " + sender.getName());
                    } else if (args.length >= 2) {
                        msg.send(sender, "sezon.zly-kod");
                    } else {
                        msg.send(sender, "sezon.reset-potwierdz", Messages.text("kod", codes.issue(key, System.currentTimeMillis())));
                    }
                }
                case "punkty" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    if (args.length < 3) {
                        msg.send(sender, "sezon.uzycie-punkty");
                        return;
                    }
                    long delta;
                    try {
                        delta = Long.parseLong(args[2]);
                    } catch (NumberFormatException e) {
                        msg.send(sender, "zla-liczba", Messages.text("wartosc", args[2]));
                        return;
                    }
                    ctx.tasks.thenSync(findPlayer(args[1]), found -> {
                        if (found.isEmpty()) {
                            msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", args[1]));
                            return;
                        }
                        var k = found.get();
                        ctx.tasks.thenSync(ctx.tasks.runAsync(() -> seasons.addPoints(k.uuid(), k.name(), delta, 0)),
                                v -> msg.send(sender, "sezon.punkty-zmienione", Messages.text("gracz", k.name()),
                                        Messages.text("punkty", delta)), e -> handleError(sender, e));
                    }, e -> handleError(sender, e));
                }
                case "przeladuj" -> {
                    if (!requirePermission(sender, PERM_ADMIN)) {
                        return;
                    }
                    try {
                        book = loadBook();
                        msg.send(sender, "sezon.przeladowano", Messages.text("questy", book.quests().size()),
                                Messages.text("wyzwania", book.challenges().size()));
                    } catch (RuntimeException e) {
                        sender.sendMessage(msg.parse("<prefix><red>Błąd w questy.yml: " + e.getMessage()));
                    }
                }
                default -> info(sender);
            }
        }

        private void info(CommandSender sender) {
            Instant now = Instant.now(ctx.clock);
            String left = TimeFormat.duration(Duration.between(now, nextReset));
            if (!(sender instanceof Player p)) {
                ctx.tasks.thenSync(ctx.tasks.supplyAsync(seasons::currentSeason), season ->
                        msg.send(sender, "sezon.info-konsola", Messages.text("sezon", season), Messages.text("czas", left)),
                        e -> handleError(sender, e));
                return;
            }
            UUID uuid = p.getUniqueId();
            ctx.tasks.thenSync(ctx.tasks.supplyAsync(() -> new Object[]{seasons.currentSeason(), seasons.entry(uuid),
                    seasons.place(uuid)}), d -> {
                @SuppressWarnings("unchecked")
                var entry = (java.util.Optional<SeasonService.Entry>) d[1];
                @SuppressWarnings("unchecked")
                var place = (java.util.Optional<Integer>) d[2];
                msg.getList("sezon.info", Messages.text("sezon", d[0]), Messages.text("czas", left),
                        Messages.text("punkty", entry.map(SeasonService.Entry::points).orElse(0L)),
                        Messages.text("krysztaly", entry.map(SeasonService.Entry::crystals).orElse(0L)),
                        Messages.text("miejsce", place.map(String::valueOf).orElse("—")),
                        Messages.text("czas_gry", TimeFormat.duration(Duration.ofSeconds(
                                entry.map(SeasonService.Entry::playtimeSeconds).orElse(0L))))).forEach(p::sendMessage);
            }, e -> handleError(p, e));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            if (args.length == 1 && sender.hasPermission(PERM_ADMIN)) {
                return List.of("reset", "punkty", "przeladuj");
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("punkty")) {
                return onlineNames();
            }
            return List.of();
        }
    }

    private long pendingOf(UUID uuid, String challengeId) {
        long[] out = {0};
        pendingChallenges.computeIfPresent(uuid, (k, m) -> {
            out[0] = m.getOrDefault(challengeId, 0L);
            return m;
        });
        return out[0];
    }

    /** Tylko testy: stan pomocniczy. */
    Map<UUID, Map<String, Long>> pending() {
        return new HashMap<>(pendingChallenges);
    }
}
