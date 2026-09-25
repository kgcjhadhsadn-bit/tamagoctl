package pl.kudlacze.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kudlacze.core.currency.KcItems;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.death.DeathModule;
import pl.kudlacze.core.hooks.MetaReader;
import pl.kudlacze.core.perks.PerksModule;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testy na MockBukkit: plugin ładowany w całości na bazie H2. */
class PluginIntegrationTest {

    private ServerMock server;
    private KudlaczeCore plugin;
    private CoreContext ctx;

    @BeforeEach
    void setUp() {
        System.setProperty(KudlaczeCore.TEST_JDBC, TestDb.url());
        System.setProperty("CFG_SERVER_NAME", "survival");
        System.setProperty("CFG_KC_ITEM_SECRET", "sekret-testowy-kudlaczy");
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(KudlaczeCore.class);
        ctx = plugin.context();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        System.clearProperty(KudlaczeCore.TEST_JDBC);
    }

    private static String text(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> drain(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            out.add(text(c));
        }
        return out;
    }

    private int kcInInventory(PlayerMock p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (ctx.kcItems.isTagged(it)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    @Test
    void pluginEnablesCoreModules() {
        assertTrue(plugin.enabledModules().containsAll(List.of("waluta", "perki", "czat", "info")));
        assertEquals("survival", ctx.server());
    }

    @Test
    void kcItemIsSignedAndForgeryIsDetected() {
        UUID batch = UUID.randomUUID();
        ItemStack real = ctx.kcItems.create(batch, 5);
        assertEquals(Material.AMETHYST_SHARD, real.getType());
        assertEquals(Optional.of(batch), ctx.kcItems.validBatch(real));

        KcItems otherServer = new KcItems("kudlacze", "inny-sekret", "x", List.of());
        ItemStack forged = otherServer.create(batch, 5);
        assertTrue(ctx.kcItems.isTagged(forged));
        assertTrue(ctx.kcItems.validBatch(forged).isEmpty(), "podpis innym sekretem = podróbka");

        assertFalse(ctx.kcItems.isTagged(new ItemStack(Material.AMETHYST_SHARD, 3)), "zwykły ametyst to nie KC");
    }

    @Test
    void withdrawAndDepositRoundTrip() {
        PlayerMock p = server.addPlayer("Kudlacz");
        ctx.kc.give(p.getUniqueId(), 100, KcService.TxType.QUEST, "test").join();

        p.performCommand("wyplac 70");
        assertEquals(70, kcInInventory(p));
        assertEquals(30L, ctx.kc.balance(p.getUniqueId()).join());

        p.performCommand("wplac 20");
        assertEquals(50, kcInInventory(p));
        assertEquals(50L, ctx.kc.balance(p.getUniqueId()).join());

        p.performCommand("wplac");
        assertEquals(0, kcInInventory(p));
        assertEquals(100L, ctx.kc.balance(p.getUniqueId()).join());
    }

    @Test
    void duplicatedItemsAreConfiscatedOnDeposit() {
        PlayerMock p = server.addPlayer("Dupek");
        ctx.kc.give(p.getUniqueId(), 10, KcService.TxType.QUEST, "test").join();
        p.performCommand("wyplac 10");
        ItemStack stack = p.getInventory().all(Material.AMETHYST_SHARD).values().iterator().next().clone();
        p.getInventory().addItem(stack); // „zduplikowane” 10 sztuk z tej samej partii
        drain(p);
        p.performCommand("wplac");
        assertEquals(10L, ctx.kc.balance(p.getUniqueId()).join(), "wpłacona tylko oryginalna partia");
        assertEquals(0, kcInInventory(p));
        assertTrue(drain(p).stream().anyMatch(m -> m.contains("skonfiskowano")));
    }

    @Test
    void forgedItemsAreConfiscated() {
        PlayerMock p = server.addPlayer("Falsz");
        KcItems forger = new KcItems("kudlacze", "zly-sekret", "x", List.of());
        p.getInventory().addItem(forger.create(UUID.randomUUID(), 64));
        p.performCommand("wplac");
        assertEquals(0L, ctx.kc.balance(p.getUniqueId()).join());
        assertEquals(0, kcInInventory(p));
    }

    @Test
    void withdrawNeedsFreeSpace() {
        PlayerMock p = server.addPlayer("Pelny");
        ctx.kc.give(p.getUniqueId(), 10, KcService.TxType.QUEST, "test").join();
        for (int i = 0; i < 36; i++) {
            p.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
        p.performCommand("wyplac 5");
        assertEquals(10L, ctx.kc.balance(p.getUniqueId()).join());
    }

    @Test
    void deathKicksAndLocksUnlessBypassed() {
        ctx.config.raw().set("smierc.tryb", "KICK_AND_LOCK");
        ctx.config.raw().set("smierc.blokada-minut", 5);
        DeathModule death = new DeathModule(ctx);
        death.enable();

        PlayerMock p = server.addPlayer("Pechowiec");
        p.setHealth(0);
        server.getScheduler().performTicks(2);
        assertFalse(p.isOnline(), "po śmierci gracz jest wyrzucany");

        AsyncPlayerPreLoginEvent login = new AsyncPlayerPreLoginEvent(p.getName(), InetAddress.getLoopbackAddress(),
                p.getUniqueId());
        death.onPreLogin(login);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, login.getLoginResult());
        assertTrue(text(login.kickMessage()).contains("Możesz wrócić za"));

        PlayerMock uvip = server.addPlayer("Uvip");
        uvip.addAttachment(plugin, DeathModule.BYPASS, true);
        uvip.setHealth(0);
        server.getScheduler().performTicks(2);
        assertTrue(uvip.isOnline(), "UVIP+ nie jest wyrzucany");
    }

    @Test
    void brukConvertsCobbleForTytan() {
        PlayerMock p = server.addPlayer("Tytan");
        p.addAttachment(plugin, PerksModule.PERM_BRUK, true);
        p.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64), new ItemStack(Material.COBBLESTONE, 10),
                new ItemStack(Material.DIRT, 5));
        p.performCommand("bruk");
        assertEquals(74, p.getInventory().all(Material.STONE).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertFalse(p.getInventory().contains(Material.COBBLESTONE));
        assertTrue(p.getInventory().contains(Material.DIRT, 5));
    }

    @Test
    void brukRequiresPermission() {
        PlayerMock p = server.addPlayer("Zwykly");
        p.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 10));
        p.performCommand("bruk");
        assertTrue(p.getInventory().contains(Material.COBBLESTONE, 10));
    }

    @Test
    void expMultiplierFromMeta() {
        ctx.meta = new MetaReader() {
            @Override
            public Optional<String> meta(org.bukkit.entity.Player player, String key) {
                return PerksModule.META_EXP.equals(key) ? Optional.of("1.5") : Optional.empty();
            }

            @Override
            public String prefix(org.bukkit.entity.Player player) {
                return "";
            }

            @Override
            public String suffix(org.bukkit.entity.Player player) {
                return "";
            }

            @Override
            public String primaryGroup(org.bukkit.entity.Player player) {
                return "svip";
            }
        };
        PlayerMock p = server.addPlayer("Svip");
        int total = 0;
        for (int i = 0; i < 10; i++) {
            PlayerExpChangeEvent e = new PlayerExpChangeEvent(p, 3);
            server.getPluginManager().callEvent(e);
            total += e.getAmount();
        }
        assertEquals(45, total, "x1,5 z 30 XP = 45 (reszty ułamkowe się sumują)");
    }

    @Test
    void playerJoinIsRecordedInDirectory() {
        PlayerMock p = server.addPlayer("Nowy");
        assertEquals(Optional.of("Nowy"), ctx.players.nameOf(p.getUniqueId()));
        assertEquals(p.getUniqueId(), ctx.players.byName("nowy").orElseThrow().uuid());
    }
}
