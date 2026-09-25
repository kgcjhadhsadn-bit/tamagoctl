package pl.kudlacze.core.seasons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.TestDb;
import pl.kudlacze.core.util.Schedules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Questy Kwatermistrza i wyzwania na MockBukkit (baza H2, zadania synchroniczne). */
class SeasonsModuleTest {

    private ServerMock server;
    private CoreContext ctx;
    private SeasonsModule module;
    private SeasonService seasons;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        System.setProperty(KudlaczeCore.TEST_JDBC, TestDb.url());
        System.setProperty("CFG_SERVER_NAME", "seasons");
        System.setProperty("CFG_KC_ITEM_SECRET", "sekret-testowy-kudlaczy");
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        KudlaczeCore plugin = MockBukkit.load(KudlaczeCore.class);
        ctx = plugin.context();
        module = new SeasonsModule(ctx);
        module.enable();
        seasons = ctx.service(SeasonService.class);
        player = server.addPlayer("Kudlacz");
        drain();
    }

    @AfterEach
    void tearDown() {
        module.disable();
        MockBukkit.unmock();
        System.clearProperty(KudlaczeCore.TEST_JDBC);
    }

    private List<String> drain() {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = player.nextComponentMessage()) != null) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    private int count(Material m) {
        int n = 0;
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it != null && it.getType() == m) {
                n += it.getAmount();
            }
        }
        return n;
    }

    @Test
    void deliveringQuestGivesPointsAndKcAndTakesItems() {
        QuestBook.Quest drewno = module.loadBook().quests().get("drewno");
        player.getInventory().addItem(new ItemStack(Material.OAK_LOG, 64), new ItemStack(Material.BIRCH_LOG, 64),
                new ItemStack(Material.SPRUCE_LOG, 10));
        module.deliver(player, drewno);
        assertEquals(10, count(Material.SPRUCE_LOG) + count(Material.OAK_LOG) + count(Material.BIRCH_LOG));
        assertEquals(drewno.points(), seasons.entry(player.getUniqueId()).orElseThrow().points());
        assertEquals(1L, ctx.kc.balance(player.getUniqueId()).join());
        assertTrue(drain().stream().anyMatch(m -> m.contains("wykonany")));
    }

    @Test
    void dailyLimitKeepsItemsInInventory() {
        QuestBook.Quest drewno = module.loadBook().quests().get("drewno");
        for (int i = 0; i < drewno.dailyLimit(); i++) {
            player.getInventory().addItem(new ItemStack(Material.OAK_LOG, 64), new ItemStack(Material.OAK_LOG, 64));
            module.deliver(player, drewno);
        }
        player.getInventory().addItem(new ItemStack(Material.OAK_LOG, 64), new ItemStack(Material.OAK_LOG, 64));
        drain();
        module.deliver(player, drewno);
        assertEquals(128, count(Material.OAK_LOG));
        assertTrue(drain().stream().anyMatch(m -> m.contains("dziennie")));
        assertEquals(drewno.points() * drewno.dailyLimit(), seasons.entry(player.getUniqueId()).orElseThrow().points());
    }

    @Test
    void weeklyKcLimitStillAwardsPoints() {
        long weekStart = Schedules.weekStart(Instant.now(ctx.clock), ctx.config.zone()).toEpochMilli();
        seasons.logQuest(player.getUniqueId(), "inny", 1, 0, 10, weekStart + 1);
        QuestBook.Quest zelazo = module.loadBook().quests().get("zelazo");
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 32));
        module.deliver(player, zelazo);
        assertEquals(0L, ctx.kc.balance(player.getUniqueId()).join());
        assertEquals(zelazo.points(), seasons.entry(player.getUniqueId()).orElseThrow().points());
        assertTrue(drain().stream().anyMatch(m -> m.contains("limit")));
    }

    @Test
    void miningChallengeCompletesAndGrantsCrystals() {
        QuestBook.Challenge gornik = module.loadBook().challenges().get("gornik");
        Block ore = server.getWorld("world").getBlockAt(0, 10, 0);
        ore.setType(Material.DEEPSLATE_DIAMOND_ORE);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
        for (int i = 0; i < gornik.goal(); i++) {
            module.onBreak(new BlockBreakEvent(ore, player));
        }
        module.flushChallenges();
        SeasonService.Entry e = seasons.entry(player.getUniqueId()).orElseThrow();
        assertEquals(gornik.crystals(), e.crystals());
        assertEquals(gornik.points(), e.points());
        // kolejne wykopanie po ukończeniu nie daje drugiej nagrody
        module.onBreak(new BlockBreakEvent(ore, player));
        module.flushChallenges();
        assertEquals(gornik.crystals(), seasons.entry(player.getUniqueId()).orElseThrow().crystals());
    }

    @Test
    void silkTouchMiningDoesNotCount() {
        Block ore = server.getWorld("world").getBlockAt(0, 10, 0);
        ore.setType(Material.DIAMOND_ORE);
        ItemStack silk = new ItemStack(Material.DIAMOND_PICKAXE);
        silk.addEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH, 1);
        player.getInventory().setItemInMainHand(silk);
        module.onBreak(new BlockBreakEvent(ore, player));
        assertTrue(module.pending().isEmpty());
    }
}
