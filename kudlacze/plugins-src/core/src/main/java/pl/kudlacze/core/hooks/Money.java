package pl.kudlacze.core.hooks;

import org.bukkit.OfflinePlayer;

/** Pieniądze z gry (EssentialsX przez Vault) — tylko do wyświetlania i nagród w złotych. */
public interface Money {

    double balance(OfflinePlayer player);

    String format(double amount);

    boolean deposit(OfflinePlayer player, double amount);

    Money NONE = new Money() {
        @Override
        public double balance(OfflinePlayer player) {
            return 0;
        }

        @Override
        public String format(double amount) {
            return String.format(java.util.Locale.ROOT, "%.0f zł", amount);
        }

        @Override
        public boolean deposit(OfflinePlayer player, double amount) {
            return false;
        }
    };
}
