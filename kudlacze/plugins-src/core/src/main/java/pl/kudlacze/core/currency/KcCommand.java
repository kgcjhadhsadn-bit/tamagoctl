package pl.kudlacze.core.currency;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalLong;

/**
 * /kc — saldo Kłaków Czarodzieja.
 * <pre>
 * /kc                          własne saldo
 * /kc &lt;nick&gt;                   saldo innego gracza (kudlacze.kc.podglad)
 * /kc przelej &lt;nick&gt; &lt;ilość&gt;   przelew
 * /kc historia [nick]          ostatnie transakcje
 * /kc daj|zabierz|ustaw &lt;nick&gt; &lt;ilość&gt;   admin (kudlacze.kc.admin)
 * </pre>
 */
public final class KcCommand extends BaseCommand {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public KcCommand(CoreContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            Player p = requirePlayer(sender);
            if (p != null) {
                ctx.tasks.thenSync(ctx.kc.balance(p.getUniqueId()),
                        bal -> msg.send(p, "kc.saldo", Messages.text("saldo", bal), Messages.text("slowo", TimeFormat.klaki(bal))),
                        e -> handleError(p, e));
            }
            return;
        }
        switch (args[0].toLowerCase()) {
            case "przelej" -> transfer(sender, args);
            case "historia" -> history(sender, args);
            case "daj", "zabierz", "ustaw" -> admin(sender, args);
            case "pomoc" -> msg.getList("kc.pomoc").forEach(sender::sendMessage);
            default -> other(sender, args[0]);
        }
    }

    private void other(CommandSender sender, String name) {
        if (!requirePermission(sender, "kudlacze.kc.podglad")) {
            return;
        }
        ctx.tasks.thenSync(findPlayer(name), found -> {
            if (found.isEmpty()) {
                msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", name));
                return;
            }
            ctx.tasks.thenSync(ctx.kc.balance(found.get().uuid()),
                    bal -> msg.send(sender, "kc.saldo-innego", Messages.text("gracz", found.get().name()),
                            Messages.text("saldo", bal)),
                    e -> handleError(sender, e));
        }, e -> handleError(sender, e));
    }

    private void transfer(CommandSender sender, String[] args) {
        Player p = requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (args.length < 3) {
            msg.send(p, "kc.uzycie-przelej");
            return;
        }
        OptionalLong amount = parsePositive(p, args[2]);
        if (amount.isEmpty()) {
            return;
        }
        ctx.tasks.thenSync(findPlayer(args[1]), found -> {
            if (found.isEmpty()) {
                msg.send(p, "nie-znaleziono-gracza", Messages.text("gracz", args[1]));
                return;
            }
            if (found.get().uuid().equals(p.getUniqueId())) {
                msg.send(p, "kc.przelew-do-siebie");
                return;
            }
            ctx.tasks.thenSync(ctx.kc.transfer(p.getUniqueId(), found.get().uuid(), amount.getAsLong()), v -> {
                msg.send(p, "kc.przelew-wyslany", Messages.text("ilosc", amount.getAsLong()),
                        Messages.text("gracz", found.get().name()));
                Player target = p.getServer().getPlayer(found.get().uuid());
                if (target != null) {
                    msg.send(target, "kc.przelew-otrzymany", Messages.text("ilosc", amount.getAsLong()),
                            Messages.text("gracz", p.getName()));
                }
            }, e -> handleError(p, e));
        }, e -> handleError(p, e));
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!requirePermission(sender, "kudlacze.kc.podglad")) {
                return;
            }
            ctx.tasks.thenSync(findPlayer(args[1]), found -> found.ifPresentOrElse(
                    k -> showHistory(sender, k.uuid(), k.name()),
                    () -> msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", args[1]))),
                    e -> handleError(sender, e));
            return;
        }
        Player p = requirePlayer(sender);
        if (p != null) {
            showHistory(p, p.getUniqueId(), p.getName());
        }
    }

    private void showHistory(CommandSender sender, java.util.UUID uuid, String name) {
        ctx.tasks.thenSync(ctx.kc.history(uuid, 10), list -> {
            msg.send(sender, "kc.historia-naglowek", Messages.text("gracz", name));
            if (list.isEmpty()) {
                msg.send(sender, "kc.historia-pusta");
            }
            for (KcRepository.Transaction t : list) {
                String when = DATE.format(Instant.ofEpochMilli(t.time()).atZone(ctx.config.zone()));
                msg.send(sender, "kc.historia-wpis",
                        Messages.text("czas", when),
                        Messages.parsed("zmiana", t.change() >= 0 ? "<green>+" + t.change() : "<red>" + t.change()),
                        Messages.text("saldo", t.balanceAfter()),
                        Messages.text("typ", t.type()));
            }
        }, e -> handleError(sender, e));
    }

    private void admin(CommandSender sender, String[] args) {
        if (!requirePermission(sender, "kudlacze.kc.admin")) {
            return;
        }
        if (args.length < 3) {
            msg.send(sender, "kc.uzycie-admin");
            return;
        }
        String action = args[0].toLowerCase();
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            msg.send(sender, "zla-liczba", Messages.text("wartosc", args[2]));
            return;
        }
        if (amount < 0 || (amount == 0 && !action.equals("ustaw"))) {
            msg.send(sender, "zla-liczba", Messages.text("wartosc", args[2]));
            return;
        }
        String source = "admin:" + sender.getName();
        ctx.tasks.thenSync(findPlayer(args[1]), found -> {
            if (found.isEmpty()) {
                msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", args[1]));
                return;
            }
            var target = found.get();
            var future = switch (action) {
                case "daj" -> ctx.kc.give(target.uuid(), amount, KcService.TxType.ADMIN, source);
                case "zabierz" -> ctx.kc.take(target.uuid(), amount, KcService.TxType.ADMIN, source);
                default -> ctx.kc.set(target.uuid(), amount, source);
            };
            ctx.tasks.thenSync(future, bal -> msg.send(sender, "kc.admin-ok",
                    Messages.text("gracz", target.name()), Messages.text("saldo", bal)), e -> handleError(sender, e));
        }, e -> handleError(sender, e));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> base = new java.util.ArrayList<>(List.of("przelej", "historia", "pomoc"));
            if (sender.hasPermission("kudlacze.kc.admin")) {
                base.addAll(List.of("daj", "zabierz", "ustaw"));
            }
            if (sender.hasPermission("kudlacze.kc.podglad")) {
                base.addAll(onlineNames());
            }
            return base;
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("pomoc")) {
            return onlineNames();
        }
        return List.of();
    }
}
