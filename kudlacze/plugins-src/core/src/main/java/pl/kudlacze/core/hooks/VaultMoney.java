package pl.kudlacze.core.hooks;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** {@link Money} przez Vault (EssentialsX) — ładowana tylko, gdy plugin Vault jest włączony. */
public final class VaultMoney implements Money {

    private final Economy eco;

    private VaultMoney(Economy eco) {
        this.eco = eco;
    }

    /** Null, gdy żaden plugin nie zarejestrował ekonomii. */
    public static Money create() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : new VaultMoney(rsp.getProvider());
    }

    @Override
    public double balance(OfflinePlayer player) {
        return eco.getBalance(player);
    }

    @Override
    public String format(double amount) {
        return eco.format(amount);
    }

    @Override
    public boolean deposit(OfflinePlayer player, double amount) {
        return eco.depositPlayer(player, amount).transactionSuccess();
    }
}
