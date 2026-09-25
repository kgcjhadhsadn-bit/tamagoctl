package pl.kudlacze.core.currency;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;

import java.util.List;
import java.util.OptionalLong;

/** /wyplac &lt;ilość&gt; — wypłata KC z konta jako fizyczne przedmioty (do handlu między graczami). */
public final class WithdrawCommand extends BaseCommand {

    private static final int MAX = 64 * 9;

    public WithdrawCommand(CoreContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (args.length < 1) {
            msg.send(p, "kc.uzycie-wyplac");
            return;
        }
        OptionalLong parsed = parsePositive(p, args[0]);
        if (parsed.isEmpty()) {
            return;
        }
        long amount = parsed.getAsLong();
        if (amount > MAX) {
            msg.send(p, "kc.wyplata-limit", Messages.text("max", MAX));
            return;
        }
        int stacksNeeded = (int) ((amount + 63) / 64);
        if (freeSlots(p) < stacksNeeded) {
            msg.send(p, "kc.brak-miejsca", Messages.text("sloty", stacksNeeded));
            return;
        }
        ctx.tasks.thenSync(ctx.kc.withdraw(p.getUniqueId(), amount), batch -> {
            long rest = amount;
            while (rest > 0) {
                int n = (int) Math.min(rest, 64);
                ItemStack item = ctx.kcItems.create(batch, n);
                p.getInventory().addItem(item).values()
                        .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                rest -= n;
            }
            msg.send(p, "kc.wyplacono", Messages.text("ilosc", amount),
                    Messages.text("saldo", ctx.kc.cachedBalance(p.getUniqueId())));
        }, e -> handleError(p, e));
    }

    private static int freeSlots(Player p) {
        int free = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType().isAir()) {
                free++;
            }
        }
        return free;
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? List.of("1", "10", "64") : List.of();
    }
}
