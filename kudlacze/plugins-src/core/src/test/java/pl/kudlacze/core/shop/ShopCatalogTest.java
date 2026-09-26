package pl.kudlacze.core.shop;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {

    @Test
    void bundledConfigHasShopDisabled() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        ShopCatalog c = ShopCatalog.load(cfg.getConfigurationSection("sklep"));
        assertFalse(c.enabled());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void cosmeticItemsAreAccepted() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("sklep.wlaczony", true);
        cfg.set("sklep.pozycje.tytul_kudlaty.typ", "tytul");
        cfg.set("sklep.pozycje.tytul_kudlaty.nazwa", "Tytuł [Kudłaty]");
        cfg.set("sklep.pozycje.tytul_kudlaty.cena-grosze", 999);
        cfg.set("sklep.pozycje.tytul_kudlaty.nadaje", "kudlaty");
        cfg.set("sklep.pozycje.iskry.typ", "CZASTECZKI");
        cfg.set("sklep.pozycje.iskry.cena-grosze", 499);
        ShopCatalog c = ShopCatalog.load(cfg.getConfigurationSection("sklep"));
        assertTrue(c.enabled());
        assertEquals(2, c.items().size());
        assertEquals("TYTUL", c.items().get("tytul_kudlaty").type());
    }

    @Test
    void gameplayAdvantagesAreRejected() {
        for (String type : new String[]{"RANGA", "KC", "PRZEDMIOT", "WALUTA", "DZIALKA", ""}) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("sklep.pozycje.x.typ", type);
            cfg.set("sklep.pozycje.x.cena-grosze", 100);
            assertThrows(IllegalArgumentException.class, () -> ShopCatalog.load(cfg.getConfigurationSection("sklep")), type);
        }
    }

    @Test
    void priceMustBePositive() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("sklep.pozycje.x.typ", "KOLOR_NICKU");
        assertThrows(IllegalArgumentException.class, () -> ShopCatalog.load(cfg.getConfigurationSection("sklep")));
    }
}
