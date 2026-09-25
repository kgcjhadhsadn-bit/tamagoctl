package pl.kudlacze.core.currency;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.module.CoreModule;

/** Moduł Kłaków Czarodzieja: komendy /kc, /wplac, /wyplac i placeholder %kudlacze_kc%. */
public final class CurrencyModule implements CoreModule, Listener {

    private final CoreContext ctx;

    public CurrencyModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "waluta";
    }

    @Override
    public void enable() {
        KudlaczeCore.command(ctx, "kc", new KcCommand(ctx));
        KudlaczeCore.command(ctx, "wplac", new DepositCommand(ctx));
        KudlaczeCore.command(ctx, "wyplac", new WithdrawCommand(ctx));
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin);
        ctx.placeholders.register("kc", p -> p == null ? "0" : String.valueOf(ctx.kc.cachedBalance(p.getUniqueId())));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ctx.kc.balance(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ctx.kc.forget(event.getPlayer().getUniqueId());
    }
}
