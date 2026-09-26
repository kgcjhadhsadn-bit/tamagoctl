package pl.kudlacze.core.shop;

import org.bukkit.command.CommandSender;
import pl.kudlacze.core.CoreContext;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.command.BaseCommand;
import pl.kudlacze.core.config.Messages;
import pl.kudlacze.core.module.CoreModule;
import pl.kudlacze.core.titles.TitleService;

import java.util.List;

/**
 * Sklep kosmetyczny — domyślnie WYŁĄCZONY ({@code sklep.wlaczony: false}). Zawiera tylko strukturę: zwalidowany
 * katalog (wyłącznie kosmetyki), tabelę pozycji i rejestr wydań. {@code /sklep} informuje, że wszystko zdobywa
 * się w grze. Po ewentualnym włączeniu personel wydaje zakupione kosmetyki komendą
 * {@code /sklep wydaj <nick> <pozycja> <nr zamówienia>} (tytuł z {@code nadaje}), a każde wydanie trafia do
 * {@code sklep_zakupy}. Integracji płatności brak — to decyzja właściciela serwera (docs/MONETYZACJA.md).
 */
public final class ShopModule implements CoreModule {

    public static final String PERM_ADMIN = "kudlacze.sklep.admin";

    private final CoreContext ctx;
    private ShopCatalog catalog;

    public ShopModule(CoreContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String id() {
        return "sklep";
    }

    @Override
    public void enable() {
        catalog = ShopCatalog.load(ctx.config.section("sklep"));
        KudlaczeCore.command(ctx, "sklep", new ShopCommand());
        ctx.tasks.runAsync(this::syncItems);
    }

    /** Odzwierciedla katalog w tabeli sklep_pozycje (aktywna tylko przy włączonym sklepie). */
    private void syncItems() {
        for (ShopCatalog.Item i : catalog.items().values()) {
            if (ctx.sql.update("UPDATE sklep_pozycje SET typ = ?, nazwa = ?, cena_grosze = ?, aktywna = ? WHERE id = ?",
                    i.type(), i.name(), i.priceGrosze(), catalog.enabled(), i.id()) == 0) {
                ctx.sql.update("INSERT INTO sklep_pozycje (id, typ, nazwa, cena_grosze, aktywna) VALUES (?, ?, ?, ?, ?)",
                        i.id(), i.type(), i.name(), i.priceGrosze(), catalog.enabled());
            }
        }
    }

    private final class ShopCommand extends BaseCommand {
        ShopCommand() {
            super(ShopModule.this.ctx);
        }

        @Override
        protected void execute(CommandSender sender, String label, String[] args) {
            if (!catalog.enabled()) {
                msg.send(sender, "sklep.wylaczony");
                return;
            }
            if (args.length >= 4 && args[0].equalsIgnoreCase("wydaj")) {
                if (!requirePermission(sender, PERM_ADMIN)) {
                    return;
                }
                ShopCatalog.Item item = catalog.items().get(args[2]);
                if (item == null) {
                    msg.send(sender, "sklep.brak-pozycji", Messages.text("pozycja", args[2]));
                    return;
                }
                TitleService titles = ctx.service(TitleService.class);
                ctx.tasks.thenSync(findPlayer(args[1]), found -> {
                    if (found.isEmpty()) {
                        msg.send(sender, "nie-znaleziono-gracza", Messages.text("gracz", args[1]));
                        return;
                    }
                    var k = found.get();
                    ctx.tasks.thenSync(ctx.tasks.runAsync(() -> {
                        long now = ctx.clock.millis();
                        ctx.sql.update("INSERT INTO sklep_zakupy (uuid, pozycja, zamowienie, czas) VALUES (?, ?, ?, ?)",
                                k.uuid(), item.id(), args[3], now);
                        if (item.type().equals("TYTUL") && titles != null && !item.grants().isBlank()) {
                            titles.grant(k.uuid(), item.grants(), "Sklep (" + args[3] + ")", now);
                        }
                    }), v -> msg.send(sender, "sklep.wydano", Messages.text("gracz", k.name()),
                            Messages.text("pozycja", item.name())), e -> handleError(sender, e));
                }, e -> handleError(sender, e));
                return;
            }
            msg.send(sender, "sklep.naglowek");
            for (ShopCatalog.Item i : catalog.items().values()) {
                msg.send(sender, "sklep.pozycja", Messages.parsed("nazwa", i.name()),
                        Messages.text("cena", String.format(java.util.Locale.ROOT, "%.2f", i.priceGrosze() / 100.0)));
            }
        }

        @Override
        protected List<String> complete(CommandSender sender, String[] args) {
            return List.of();
        }
    }
}
