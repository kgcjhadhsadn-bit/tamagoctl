package pl.kudlacze.core.currency;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fizyczny przedmiot „Kłak Czarodzieja”: odłamek ametystu z identyfikatorem partii
 * i podpisem HMAC w PersistentDataContainer. Bez sekretu serwera nie da się podrobić
 * przedmiotu (np. /give z NBT), a baza pilnuje, żeby partii nie wpłacić więcej razy
 * niż ją wypłacono (ochrona przed duplikacją).
 */
public final class KcItems {

    /** Wartość custom_model_data (string) — resource pack podmienia wygląd odłamka ametystu. */
    public static final String MODEL_ID = "kudlacze:klak_czarodzieja";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NamespacedKey keyBatch;
    private final NamespacedKey keySignature;
    private final byte[] secret;
    private final String displayName;
    private final List<String> lore;

    public KcItems(String namespace, String secret, String displayName, List<String> lore) {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalStateException("Brak sekretu KC_ITEM_SECRET — ustaw go w .env");
        }
        this.keyBatch = new NamespacedKey(namespace, "kc_partia");
        this.keySignature = new NamespacedKey(namespace, "kc_podpis");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.displayName = displayName;
        this.lore = lore;
    }

    public ItemStack create(UUID batchId, int amount) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(displayName).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> (Component) MM.deserialize(l).decoration(TextDecoration.ITALIC, false)).toList());
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(MODEL_ID));
        meta.setCustomModelDataComponent(cmd);
        meta.setEnchantmentGlintOverride(true);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyBatch, PersistentDataType.STRING, batchId.toString());
        pdc.set(keySignature, PersistentDataType.STRING, sign(batchId));
        item.setItemMeta(meta);
        return item;
    }

    /** Czy przedmiot wygląda na KC (ma znacznik partii) — niezależnie od poprawności podpisu. */
    public boolean isTagged(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(keyBatch, PersistentDataType.STRING);
    }

    /** Identyfikator partii, jeśli przedmiot jest prawdziwym KC (poprawny podpis). */
    public Optional<UUID> validBatch(ItemStack item) {
        if (!isTagged(item)) {
            return Optional.empty();
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(keyBatch, PersistentDataType.STRING);
        String sig = pdc.get(keySignature, PersistentDataType.STRING);
        try {
            UUID batch = UUID.fromString(id);
            if (sig != null && MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                    sign(batch).getBytes(StandardCharsets.UTF_8))) {
                return Optional.of(batch);
            }
        } catch (IllegalArgumentException ignored) {
            // uszkodzony identyfikator — traktujemy jak podróbkę
        }
        return Optional.empty();
    }

    String sign(UUID batchId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal(("kc:" + batchId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC niedostępny", e);
        }
    }
}
