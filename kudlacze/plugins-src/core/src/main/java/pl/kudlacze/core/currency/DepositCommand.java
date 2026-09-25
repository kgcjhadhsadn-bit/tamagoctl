package pl.kudlacze.core.currency;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /wplac [ilość|wszystko] — wpłata fizycznych Kłaków Czarodzieja z ekwipunku na konto.
 * Przedmioty są zabierane z ekwipunku PRZED zapisem w bazie (brak wyścigu przy przekładaniu),
 * podróbki (zły podpis) i duplikaty (wyczerpana partia) są konfiskowane i zgłaszane personelowi.
 */
public final class DepositCommand extends BaseCommand {

    public DepositCommand(CoreContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        long limit = Long.MAX_VALUE;
        if (args.length >= 1 && !args[0].equalsIgnoreCase("wszystko")) {
            var parsed = parsePositive(p, args[0]);
            if (parsed.isEmpty()) {
                return;
            }
            limit = parsed.getAsLong();
        }
        PlayerInventory inv = p.getInventory();
        Map<UUID, Long> perBatch = new LinkedHashMap<>();
        int forged = 0;
        long taken = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (int slot = 0; slot < contents.length && taken < limit; slot++) {
            ItemStack item = contents[slot];
            if (!ctx.kcItems.isTagged(item)) {
                continue;
            }
            var batch = ctx.kcItems.validBatch(item);
            if (batch.isEmpty()) {
                forged += item.getAmount();
                inv.setItem(slot, null);
                continue;
            }
            int use = (int) Math.min(item.getAmount(), limit - taken);
            taken += use;
            perBatch.merge(batch.get(), (long) use, Long::sum);
            if (use == item.getAmount()) {
                inv.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - use);
                inv.setItem(slot, item);
            }
        }
        if (forged > 0) {
            ctx.plugin.getLogger().warning("Skonfiskowano " + forged + " podrobionych KC graczowi " + p.getName());
            msg.send(p, "kc.podrobka", Messages.text("ilosc", forged));
            alertStaff(p, forged);
        }
        if (perBatch.isEmpty()) {
            if (forged == 0) {
                msg.send(p, "kc.brak-przedmiotow");
            }
            return;
        }
        for (Map.Entry<UUID, Long> e : perBatch.entrySet()) {
            UUID batch = e.getKey();
            long amount = e.getValue();
            ctx.tasks.thenSync(ctx.kc.deposit(p.getUniqueId(), batch, amount), result -> {
                long credited = result.map(KcRepository.Deposit::credited).orElse(0L);
                if (credited > 0) {
                    msg.send(p, "kc.wplacono", Messages.text("ilosc", credited),
                            Messages.text("saldo", result.get().balanceAfter()));
                }
                long duplicated = amount - credited;
                if (duplicated > 0) {
                    ctx.plugin.getLogger().warning("Wykryto zduplikowane KC (partia " + batch + ", " + duplicated
                            + " szt.) u gracza " + p.getName() + " — skonfiskowano");
                    msg.send(p, "kc.duplikat", Messages.text("ilosc", duplicated));
                    alertStaff(p, (int) duplicated);
                }
            }, error -> {
                // błąd bazy: oddajemy przedmioty, żeby gracz nic nie stracił
                long rest = amount;
                while (rest > 0) {
                    int n = (int) Math.min(rest, 64);
                    ItemStack back = ctx.kcItems.create(batch, n);
                    p.getInventory().addItem(back).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                    rest -= n;
                }
                handleError(p, error);
            });
        }
    }

    private void alertStaff(Player offender, int amount) {
        Bukkit.getOnlinePlayers().stream()
                .filter(s -> s.hasPermission("kudlacze.personel"))
                .forEach(s -> msg.send(s, "kc.alert-personel", Messages.text("gracz", offender.getName()),
                        Messages.text("ilosc", amount)));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? List.of("wszystko", "1", "10", "64") : List.of();
    }
}
