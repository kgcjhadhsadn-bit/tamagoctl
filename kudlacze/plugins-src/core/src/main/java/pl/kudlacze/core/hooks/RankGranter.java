package pl.kudlacze.core.hooks;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Nadawanie i sprawdzanie grup uprawnień (implementacja: LuckPerms). */
public interface RankGranter {

    /** Wszystkie grupy gracza łącznie z odziedziczonymi (np. TYTAN ma też vip…uvip). */
    CompletableFuture<Set<String>> inheritedGroups(UUID uuid);

    /** Data wygaśnięcia bezpośrednio nadanej grupy czasowej (pusta, gdy brak albo stała). */
    CompletableFuture<Optional<Instant>> directExpiry(UUID uuid, String group);

    /** Nadaje grupę na czas; jeśli gracz ma już ją czasowo — przedłuża o podany czas. */
    CompletableFuture<Instant> grantTemporary(UUID uuid, String group, Duration duration);

    CompletableFuture<Void> revoke(UUID uuid, String group);

    /** Odbiera wymienione grupy wszystkim graczom (nowa edycja). Zwraca liczbę zmienionych graczy. */
    CompletableFuture<Integer> revokeFromEveryone(Collection<String> groups);
}
