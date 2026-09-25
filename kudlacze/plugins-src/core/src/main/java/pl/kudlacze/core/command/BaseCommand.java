package pl.kudlacze.core.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.currency.KcService;
import pl.kudlacze.core.player.PlayerDirectory;
import pl.kudlacze.core.util.Tasks;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Wspólne pomocniki komend: sprawdzanie gracza, liczb, wyszukiwanie po nicku, obsługa błędów. */
public abstract class BaseCommand implements TabExecutor {

    protected final CoreContext ctx;
    protected final Messages msg;

    protected BaseCommand(CoreContext ctx) {
        this.ctx = ctx;
        this.msg = ctx.messages;
    }

    protected abstract void execute(CommandSender sender, String label, String[] args);

    protected List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }

    @Override
    public final boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                                   @NotNull String[] args) {
        try {
            execute(sender, label, args);
        } catch (RuntimeException e) {
            ctx.plugin.getLogger().log(Level.SEVERE, "Błąd komendy /" + label, e);
            msg.send(sender, "blad-wewnetrzny");
        }
        return true;
    }

    @Override
    public final List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                            @NotNull String alias, @NotNull String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return complete(sender, args).stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    protected Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        msg.send(sender, "tylko-gracz");
        return null;
    }

    protected boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        msg.send(sender, "brak-uprawnien");
        return false;
    }

    protected OptionalLong parsePositive(CommandSender sender, String value) {
        try {
            long n = Long.parseLong(value);
            if (n > 0) {
                return OptionalLong.of(n);
            }
        } catch (NumberFormatException ignored) {
            // niżej komunikat
        }
        msg.send(sender, "zla-liczba", Messages.text("wartosc", value));
        return OptionalLong.empty();
    }

    /** Gracz po nicku: najpierw online, potem z bazy (asynchronicznie). */
    protected CompletableFuture<Optional<PlayerDirectory.Known>> findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(
                    new PlayerDirectory.Known(online.getUniqueId(), online.getName())));
        }
        return ctx.tasks.supplyAsync(() -> ctx.players.byName(name));
    }

    protected List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    /** Wspólna obsługa błędów zadań asynchronicznych (brak KC, błąd bazy). */
    protected void handleError(CommandSender sender, Throwable error) {
        Throwable t = Tasks.unwrap(error);
        if (t instanceof KcService.InsufficientKcException e) {
            msg.send(sender, "kc.za-malo", Messages.text("saldo", e.balance()));
        } else if (t instanceof IllegalArgumentException e) {
            sender.sendMessage(msg.parse("<prefix><red>" + e.getMessage()));
        } else {
            ctx.plugin.getLogger().log(Level.SEVERE, "Błąd zadania", t);
            msg.send(sender, "blad-wewnetrzny");
        }
    }
}
