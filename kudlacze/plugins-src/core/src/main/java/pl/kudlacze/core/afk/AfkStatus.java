package pl.kudlacze.core.afk;

import org.bukkit.entity.Player;

/**
 * Czy gracz jest AFK — gracz AFK nie dostaje nagród za czas gry. Implementację udostępnia moduł anty-AFK
 * ({@code ctx.provide(AfkStatus.class, …)}); bez niego moduły używają prostego wykrywania bezruchu.
 */
@FunctionalInterface
public interface AfkStatus {

    boolean isAfk(Player player);
}
