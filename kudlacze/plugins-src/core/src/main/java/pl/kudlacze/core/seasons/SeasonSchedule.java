package pl.kudlacze.core.seasons;

import pl.kudlacze.core.config.CoreConfig;
import pl.kudlacze.core.util.Schedules;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Terminy z configu (wspólne dla serwerów — lobby pokazuje je na hologramie): reset sezonu
 * ({@code sezon.reset.dzien}/{@code godzina}, domyślnie 1. dzień miesiąca 12:00) i Smok Kudłaty
 * ({@code smok.dzien}/{@code godzina}, domyślnie niedziela 20:00), w strefie {@code strefa-czasowa}.
 */
public final class SeasonSchedule {

    private SeasonSchedule() {
    }

    public static Instant nextReset(CoreConfig c, Instant now) {
        return Schedules.nextMonthly(now, c.zone(), c.integer("sezon.reset.dzien", 1), resetTime(c));
    }

    public static Instant previousReset(CoreConfig c, Instant now) {
        return Schedules.previousMonthly(now, c.zone(), c.integer("sezon.reset.dzien", 1), resetTime(c));
    }

    public static Instant nextDragon(CoreConfig c, Instant now) {
        DayOfWeek day = DayOfWeek.valueOf(c.string("smok.dzien", "SUNDAY").toUpperCase(Locale.ROOT));
        return Schedules.nextWeekly(now, c.zone(), day, LocalTime.parse(c.string("smok.godzina", "20:00")));
    }

    private static LocalTime resetTime(CoreConfig c) {
        return LocalTime.parse(c.string("sezon.reset.godzina", "12:00"));
    }
}
