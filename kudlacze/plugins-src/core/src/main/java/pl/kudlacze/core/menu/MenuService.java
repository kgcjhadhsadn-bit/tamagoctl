package pl.kudlacze.core.menu;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import pl.kudlacze.core.hooks.Bedrock;
import pl.kudlacze.core.util.Tasks;

import java.util.List;

/** Otwieranie menu: GUI ze skrzyni (Java) albo formularz Floodgate (Bedrock). */
public final class MenuService implements Listener {

    private final Bedrock bedrock;
    private final Tasks tasks;

    public MenuService(Bedrock bedrock, Tasks tasks) {
        this.bedrock = bedrock;
        this.tasks = tasks;
    }

    public void open(Player player, Menu menu) {
        if (bedrock.isBedrock(player.getUniqueId()) && openForm(player, menu)) {
            return;
        }
        Holder holder = new Holder(menu);
        Inventory inv = Bukkit.createInventory(holder, menu.rows() * 9, menu.title());
        holder.inventory = inv;
        if (menu.filler() != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, menu.filler());
            }
        }
        for (Menu.Button b : menu.buttons()) {
            inv.setItem(b.slot(), b.icon());
        }
        player.openInventory(inv);
    }

    private boolean openForm(Player player, Menu menu) {
        List<Menu.Button> buttons = menu.buttons();
        String title = PlainTextComponentSerializer.plainText().serialize(menu.title());
        return bedrock.sendButtons(player.getUniqueId(), title, menu.bedrockContent(),
                buttons.stream().map(Menu.Button::label).toList(),
                index -> tasks.runSync(() -> {
                    if (index >= 0 && index < buttons.size() && player.isOnline()) {
                        buttons.get(index).action().accept(player);
                    }
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Menu.Button button = holder.menu.buttonAt(event.getRawSlot());
        if (button != null && event.getWhoClicked() instanceof Player player) {
            button.action().accept(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Właściciel ekwipunku menu — po nim rozpoznajemy nasze GUI. */
    public static final class Holder implements InventoryHolder {
        private final Menu menu;
        private Inventory inventory;

        Holder(Menu menu) {
            this.menu = menu;
        }

        public Menu menu() {
            return menu;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** Pomocnik do ikon menu. */
    public static ItemStack icon(org.bukkit.Material material, net.kyori.adventure.text.Component name,
                                 List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(l -> l.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }
}
