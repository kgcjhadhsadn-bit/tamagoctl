package pl.kudlacze.core.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Opis menu niezależny od platformy: gracz Java dostaje GUI ze skrzyni, gracz Bedrock
 * (Floodgate) — natywny formularz z przyciskami w tej samej kolejności.
 */
public final class Menu {

    /** Przycisk menu. {@code label} to czysty tekst przycisku formularza Bedrock. */
    public record Button(int slot, ItemStack icon, String label, Consumer<Player> action) {
    }

    private final Component title;
    private final String bedrockContent;
    private final int rows;
    private final List<Button> buttons;
    private final ItemStack filler;

    private Menu(Component title, String bedrockContent, int rows, List<Button> buttons, ItemStack filler) {
        this.title = title;
        this.bedrockContent = bedrockContent;
        this.rows = rows;
        this.buttons = List.copyOf(buttons);
        this.filler = filler;
    }

    public static Builder builder(Component title, int rows) {
        return new Builder(title, rows);
    }

    public Component title() {
        return title;
    }

    public String bedrockContent() {
        return bedrockContent;
    }

    public int rows() {
        return rows;
    }

    public List<Button> buttons() {
        return buttons;
    }

    public ItemStack filler() {
        return filler;
    }

    public Button buttonAt(int slot) {
        for (Button b : buttons) {
            if (b.slot() == slot) {
                return b;
            }
        }
        return null;
    }

    public static final class Builder {
        private final Component title;
        private final int rows;
        private final List<Button> buttons = new ArrayList<>();
        private String bedrockContent = "";
        private ItemStack filler;

        private Builder(Component title, int rows) {
            if (rows < 1 || rows > 6) {
                throw new IllegalArgumentException("Menu ma 1–6 rzędów");
            }
            this.title = title;
            this.rows = rows;
        }

        public Builder content(String bedrockContent) {
            this.bedrockContent = bedrockContent;
            return this;
        }

        public Builder filler(ItemStack filler) {
            this.filler = filler;
            return this;
        }

        public Builder button(int slot, ItemStack icon, String label, Consumer<Player> action) {
            if (slot < 0 || slot >= rows * 9) {
                throw new IllegalArgumentException("Slot poza menu: " + slot);
            }
            buttons.add(new Button(slot, icon, label, action));
            return this;
        }

        public Menu build() {
            return new Menu(title, bedrockContent, rows, buttons, filler);
        }
    }
}
