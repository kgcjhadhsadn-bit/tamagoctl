package pl.kudlacze.core.perks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.util.PlayerText;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perki rang: /bruk, /piec, mnożnik doświadczenia (meta LuckPerms {@code kudlacze.exp-mnoznik}),
 * kolory i gradienty na tabliczkach oraz rezerwacja slotu na pełnym serwerze.
 * /ec, /wb, /back i teleport bez czekania dają uprawnienia EssentialsX (permissions/luckperms.txt).
 */
public final class PerksModule implements CoreModule, Listener {

    public static final String PERM_BRUK = "kudlacze.bruk";
    public static final String PERM_PIEC = "kudlacze.piec";
    public static final String PERM_SIGN_COLORS = "kudlacze.tabliczki.kolory";
    public static final String PERM_GRADIENTS = "kudlacze.czat.gradienty";
    public static final String PERM_SLOT = "kudlacze.slot.rezerwacja";
    public static final String META_EXP = "kudlacze.exp-mnoznik";

    private static final Map<Material, Material> BRUK = Map.of(
            Material.COBBLESTONE, Material.STONE,
            Material.COBBLED_DEEPSLATE, Material.DEEPSLATE);

    private final CoreContext ctx;
    private final Map<UUID, Double> expRemainder = new ConcurrentHashMap<>();
    private final Map<UUID, Long> piecCooldown = new HashMap<>();
    private final Map<Material, FurnaceRecipe> recipeCache = new HashMap<>();

    public PerksModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "perki";
    }

    @Override
    public void enable() {
        KudlaczeCore.command(ctx, "bruk", new BrukCommand());
        KudlaczeCore.command(ctx, "piec", new PiecCommand());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
    }

    // ---- /bruk ---------------------------------------------------------------------

    /** Zamienia bruk z ekwipunku na kamień. Zwraca liczbę zamienionych bloków. */
    public static int convertCobble(PlayerInventory inv) {
        int converted = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || !BRUK.containsKey(it.getType())) {
                continue;
            }
            converted += it.getAmount();
            inv.setItem(i, new ItemStack(BRUK.get(it.getType()), it.getAmount()));
        }
        return converted;
    }

    private final class BrukCommand extends BaseCommand {
        BrukCommand() {
            super(PerksModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p == null || !requirePermission(p, PERM_BRUK)) {
                return;
            }
            int n = convertCobble(p.getInventory());
            msg.send(p, n > 0 ? "perki.bruk-ok" : "perki.bruk-brak", Messages.text("ilosc", n));
        }
    }

    // ---- /piec ---------------------------------------------------------------------

    Optional<FurnaceRecipe> furnaceRecipe(ItemStack input) {
        FurnaceRecipe cached = recipeCache.get(input.getType());
        if (cached != null) {
            return Optional.of(cached);
        }
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            if (it.next() instanceof FurnaceRecipe fr && fr.getInputChoice().test(new ItemStack(input.getType()))) {
                recipeCache.put(input.getType(), fr);
                return Optional.of(fr);
            }
        }
        return Optional.empty();
    }

    private final class PiecCommand extends BaseCommand {
        PiecCommand() {
            super(PerksModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p == null || !requirePermission(p, PERM_PIEC)) {
                return;
            }
            long now = ctx.clock.millis();
            long cooldownMs = ctx.config.integer("perki.piec-cooldown-sekund", 10) * 1000L;
            Long last = piecCooldown.get(p.getUniqueId());
            if (last != null && now - last < cooldownMs) {
                msg.send(p, "perki.piec-cooldown", Messages.text("sekundy", (cooldownMs - (now - last) + 999) / 1000));
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg.send(p, "perki.piec-pusta-reka");
                return;
            }
            Optional<FurnaceRecipe> recipe = hand.hasItemMeta() ? Optional.empty() : furnaceRecipe(hand);
            if (recipe.isEmpty()) {
                msg.send(p, "perki.piec-nie-da-sie");
                return;
            }
            int amount = hand.getAmount();
            ItemStack result = recipe.get().getResult().clone();
            result.setAmount(Math.min(result.getMaxStackSize(), result.getAmount() * amount));
            p.getInventory().setItemInMainHand(result);
            int xp = Math.round(recipe.get().getExperience() * amount);
            if (xp > 0) {
                p.giveExp(xp);
            }
            piecCooldown.put(p.getUniqueId(), now);
            msg.send(p, "perki.piec-ok", Messages.text("ilosc", amount),
                    Messages.component("przedmiot", Component.translatable(result.getType().translationKey())));
        }
    }

    // ---- mnożnik XP ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) {
            return;
        }
        double multiplier = ctx.meta.metaDouble(event.getPlayer(), META_EXP, 1.0);
        if (multiplier <= 1.0) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        double exact = event.getAmount() * multiplier + expRemainder.getOrDefault(uuid, 0.0);
        int whole = (int) Math.floor(exact);
        expRemainder.put(uuid, exact - whole);
        event.setAmount(whole);
    }

    /** Czysta logika mnożnika (testy): zwraca [nowa ilość, nowa reszta]. */
    public static double[] multiply(int amount, double multiplier, double remainder) {
        double exact = amount * multiplier + remainder;
        int whole = (int) Math.floor(exact);
        return new double[]{whole, exact - whole};
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        expRemainder.remove(event.getPlayer().getUniqueId());
        piecCooldown.remove(event.getPlayer().getUniqueId());
    }

    // ---- kolory na tabliczkach -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        Player p = event.getPlayer();
        PlayerText.Level level = p.hasPermission(PERM_GRADIENTS) ? PlayerText.Level.GRADIENTS
                : p.hasPermission(PERM_SIGN_COLORS) ? PlayerText.Level.COLORS : PlayerText.Level.PLAIN;
        if (level == PlayerText.Level.PLAIN) {
            return;
        }
        for (int i = 0; i < event.lines().size(); i++) {
            Component line = event.line(i);
            if (line == null) {
                continue;
            }
            String raw = PlainTextComponentSerializer.plainText().serialize(line);
            event.line(i, PlayerText.format(raw, level));
        }
    }

    // ---- rezerwacja slotu ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        Component kick = event.getKickMessage();
        if (kick == null || !(kick instanceof TranslatableComponent tc)
                || !tc.key().equals("multiplayer.disconnect.server_full")) {
            return;
        }
        if (!(event.getConnection() instanceof PlayerLoginConnection login) || login.getAuthenticatedProfile() == null) {
            return;
        }
        UUID uuid = login.getAuthenticatedProfile().getId();
        if (uuid != null && ctx.meta.hasPermissionOffline(uuid, PERM_SLOT)) {
            event.allow();
        }
    }
}
