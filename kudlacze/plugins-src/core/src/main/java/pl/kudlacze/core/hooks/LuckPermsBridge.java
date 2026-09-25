package pl.kudlacze.core.hooks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Jedyna klasa dotykająca API LuckPerms — ładowana tylko, gdy plugin LuckPerms jest włączony.
 * Rangi gameplay są nadawane jako czasowe węzły dziedziczenia (group.vip z datą wygaśnięcia).
 */
public final class LuckPermsBridge implements RankGranter, MetaReader {

    private final LuckPerms lp;

    public LuckPermsBridge() {
        this.lp = LuckPermsProvider.get();
    }

    @Override
    public CompletableFuture<Set<String>> inheritedGroups(UUID uuid) {
        return lp.getUserManager().loadUser(uuid).thenApply(user -> {
            Set<String> out = new HashSet<>();
            for (Group g : user.getInheritedGroups(QueryOptions.nonContextual())) {
                out.add(g.getName());
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Optional<Instant>> directExpiry(UUID uuid, String group) {
        return lp.getUserManager().loadUser(uuid).thenApply(user -> user.getNodes(NodeType.INHERITANCE).stream()
                .filter(n -> n.getGroupName().equalsIgnoreCase(group) && n.hasExpiry())
                .map(InheritanceNode::getExpiry)
                .findFirst());
    }

    @Override
    public CompletableFuture<Instant> grantTemporary(UUID uuid, String group, Duration duration) {
        return lp.getUserManager().modifyUser(uuid, user -> {
            InheritanceNode node = InheritanceNode.builder(group).expiry(duration).build();
            DataMutateResult.WithMergedNode res = user.data().add(node,
                    TemporaryNodeMergeStrategy.ADD_NEW_DURATION_TO_EXISTING);
            if (!res.getResult().wasSuccessful()) {
                throw new IllegalStateException("LuckPerms nie nadał grupy " + group + ": " + res.getResult());
            }
        }).thenCompose(v -> directExpiry(uuid, group)).thenApply(exp -> exp.orElse(Instant.now().plus(duration)));
    }

    @Override
    public CompletableFuture<Void> revoke(UUID uuid, String group) {
        return lp.getUserManager().modifyUser(uuid, user ->
                user.data().clear(n -> n instanceof InheritanceNode in && in.getGroupName().equalsIgnoreCase(group)));
    }

    @Override
    public CompletableFuture<Integer> revokeFromEveryone(Collection<String> groups) {
        CompletableFuture<Integer> total = CompletableFuture.completedFuture(0);
        for (String group : groups) {
            CompletableFuture<Integer> one = lp.getUserManager()
                    .searchAll(NodeMatcher.key(InheritanceNode.builder(group).build()))
                    .thenCompose(found -> {
                        CompletableFuture<?>[] all = found.keySet().stream()
                                .map(uuid -> revoke(uuid, group))
                                .toArray(CompletableFuture[]::new);
                        return CompletableFuture.allOf(all).thenApply(v -> found.size());
                    });
            total = total.thenCombine(one, Integer::sum);
        }
        return total;
    }

    // ---- MetaReader ------------------------------------------------------------------

    @Override
    public Optional<String> meta(Player player, String key) {
        CachedMetaData meta = lp.getPlayerAdapter(Player.class).getMetaData(player);
        return Optional.ofNullable(meta.getMetaValue(key));
    }

    @Override
    public String prefix(Player player) {
        String p = lp.getPlayerAdapter(Player.class).getMetaData(player).getPrefix();
        return p == null ? "" : p;
    }

    @Override
    public String suffix(Player player) {
        String s = lp.getPlayerAdapter(Player.class).getMetaData(player).getSuffix();
        return s == null ? "" : s;
    }

    @Override
    public String primaryGroup(Player player) {
        User user = lp.getPlayerAdapter(Player.class).getUser(player);
        return user.getPrimaryGroup();
    }

    @Override
    public boolean hasPermissionOffline(UUID uuid, String permission) {
        User user = lp.getUserManager().getUser(uuid);
        return user != null && user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
    }

    /** Nazwa wyświetlana grupy (setdisplayname) albo jej nazwa. */
    public String displayName(String group) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) {
            return group;
        }
        String dn = g.getDisplayName();
        return dn == null ? g.getName() : dn;
    }

    /** Rozmiar mapy wyszukiwania — do diagnostyki. */
    static int size(Map<?, ?> m) {
        return m.size();
    }
}
