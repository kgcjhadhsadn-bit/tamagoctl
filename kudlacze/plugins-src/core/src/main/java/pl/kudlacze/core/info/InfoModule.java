package pl.kudlacze.core.info;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.hooks.WorldGuardBridge;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.ranks.RankDefinition;
import pl.kudlacze.core.ranks.RankService;
import pl.kudlacze.core.util.TimeFormat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Informacje dla graczy: /regulamin, /yt (reklama kanału — tylko ranga YouTuber) oraz
 * placeholdery TAB/menu: ranga, pieniądze, działki, edycja, discord.
 */
public final class InfoModule implements CoreModule {

    public static final String PERM_YT = "kudlacze.youtuber.reklama";
    private static final Pattern CHANNEL_LINK = Pattern.compile(
            "(?i)^(https?://)?(www\\.)?(youtube\\.com/(@|c/|channel/)[\\w.-]+|youtu\\.be/[\\w-]+|twitch\\.tv/[\\w]+|tiktok\\.com/@[\\w.]+)\\S*$");
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private final CoreContext ctx;
    private final Map<UUID, Long> ytCooldown = new HashMap<>();

    public InfoModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "info";
    }

    @Override
    public void enable() {
        KudlaczeCore.command(ctx, "regulamin", new RulesCommand());
        KudlaczeCore.command(ctx, "yt", new YoutuberCommand());
        String discord = ctx.config.string("discord-zaproszenie", "");
        ctx.placeholders.register("discord", p -> discord == null || discord.startsWith("${") ? "" : discord);
        ctx.placeholders.register("edycja", p -> String.valueOf(cachedEdition));
        ctx.placeholders.register("pieniadze", p -> p == null ? "0" : ctx.money.format(ctx.money.balance(p)));
        ctx.placeholders.register("ranga", this::rankDisplay);
        boolean wg = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
        WorldGuardBridge bridge = wg ? new WorldGuardBridge() : null;
        ctx.placeholders.register("dzialki", p -> {
            if (!(p instanceof Player online)) {
                return "0";
            }
            int used = bridge == null ? 0 : bridge.countPlots(online, online.getWorld());
            int limit = (int) ctx.meta.metaDouble(online, "kudlacze.dzialki", 5);
            return used + "/" + limit;
        });
        Bukkit.getScheduler().runTaskTimerAsynchronously(ctx.plugin,
                () -> cachedEdition = ctx.kv.getLong("survival.edycja", 1), 20L, 20L * 60);
    }

    private volatile long cachedEdition = 1;

    /** Nazwa najwyższej rangi gracza w formacie z kodami § (dla TAB/scoreboardu). */
    private String rankDisplay(OfflinePlayer p) {
        if (!(p instanceof Player online)) {
            return "";
        }
        String group = ctx.meta.primaryGroup(online);
        RankService ranks = ctx.service(RankService.class);
        if (ranks != null) {
            for (RankDefinition r : ranks.ranks().values()) {
                if (r.group().equalsIgnoreCase(group)) {
                    return SECTION.serialize(ctx.messages.parse(r.displayName()));
                }
            }
        }
        return SECTION.serialize(ctx.messages.get("rangi.nazwy." + group.toLowerCase()));
    }

    private final class RulesCommand extends BaseCommand {
        RulesCommand() {
            super(InfoModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            msg.getList("regulamin").forEach(sender::sendMessage);
        }
    }

    private final class YoutuberCommand extends BaseCommand {
        YoutuberCommand() {
            super(InfoModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            Player p = requirePlayer(sender);
            if (p == null || !requirePermission(p, PERM_YT)) {
                return;
            }
            if (args.length < 1 || !CHANNEL_LINK.matcher(args[0]).matches()) {
                msg.send(p, "yt.uzycie");
                return;
            }
            long now = System.currentTimeMillis();
            long cooldown = ctx.config.integer("yt.cooldown-minut", 30) * 60_000L;
            Long last = ytCooldown.get(p.getUniqueId());
            if (last != null && now - last < cooldown) {
                msg.send(p, "yt.cooldown", Messages.text("czas", TimeFormat.duration(Duration.ofMillis(cooldown - (now - last)))));
                return;
            }
            ytCooldown.put(p.getUniqueId(), now);
            String link = args[0].startsWith("http") ? args[0] : "https://" + args[0];
            net.kyori.adventure.text.Component linkComponent = net.kyori.adventure.text.Component.text(link)
                    .color(net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link));
            Bukkit.broadcast(msg.get("yt.ogloszenie", Messages.text("gracz", p.getName()),
                    Messages.component("link", linkComponent)));
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return List.of();
        }
    }
}
