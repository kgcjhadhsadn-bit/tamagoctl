package pl.kudlacze.core.network;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.module.CoreModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Informacje o sieci przez kanał „BungeeCord” (Velocity: bungee-plugin-message-channel = true):
 * liczba graczy na serwerach ({@code %kudlacze_online_survival%}) i przenoszenie graczy.
 */
public final class NetworkModule implements CoreModule, PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";

    private final CoreContext ctx;
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private BukkitTask poller;

    public NetworkModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "siec";
    }

    @Override
    public void enable() {
        var messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(ctx.plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(ctx.plugin, CHANNEL, this);
        List<String> servers = ctx.config.list("siec.serwery");
        poller = Bukkit.getScheduler().runTaskTimer(ctx.plugin, () -> {
            Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (any == null) {
                return;
            }
            for (String s : servers) {
                send(any, "PlayerCount", s);
            }
            send(any, "PlayerCount", "ALL");
        }, 40L, 200L);
        ctx.placeholders.registerPrefix("online_", (p, server) -> {
            Integer n = counts.get(server.equalsIgnoreCase("siec") ? "ALL" : server);
            return n == null ? "?" : String.valueOf(n);
        });
        ctx.provide(NetworkModule.class, this);
    }

    @Override
    public void disable() {
        if (poller != null) {
            poller.cancel();
        }
        Bukkit.getMessenger().unregisterIncomingPluginChannel(ctx.plugin, CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(ctx.plugin, CHANNEL);
    }

    /** Przenosi gracza na inny serwer sieci. */
    public void connect(Player player, String server) {
        send(player, "Connect", server);
    }

    private void send(Player via, String sub, String arg) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(sub);
            out.writeUTF(arg);
        } catch (IOException e) {
            return;
        }
        via.sendPluginMessage(ctx.plugin, CHANNEL, bytes.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            if ("PlayerCount".equals(in.readUTF())) {
                String server = in.readUTF();
                counts.put(server, in.readInt());
            }
        } catch (IOException ignored) {
            // niepełna odpowiedź proxy — pomijamy
        }
    }

    public Map<String, Integer> counts() {
        return counts;
    }
}
