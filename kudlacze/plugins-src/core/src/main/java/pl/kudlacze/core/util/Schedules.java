package pl.kudlacze.core.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/** Harmonogramy sieci liczone w strefie czasowej serwera (Europe/Warsaw, z obsługą zmiany czasu). */
public final class Schedules {

    private Schedules() {
    }

    /** Najbliższe wystąpienie dnia tygodnia o danej godzinie ściśle po {@code now}. */
    public static Instant nextWeekly(Instant now, ZoneId zone, DayOfWeek day, LocalTime time) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = local.with(TemporalAdjusters.nextOrSame(day)).with(time);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = local.with(TemporalAdjusters.next(day)).with(time);
        }
        return candidate.toInstant();
    }

    /** Najbliższa podana godzina (codziennie) ściśle po {@code now}, np. restart o 05:00. */
    public static Instant nextDaily(Instant now, ZoneId zone, LocalTime time) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = local.toLocalDate().atTime(time).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = local.toLocalDate().plusDays(1).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    /** Najbliższy dzień miesiąca (np. 1.) o danej godzinie ściśle po {@code now}. */
    public static Instant nextMonthly(Instant now, ZoneId zone, int dayOfMonth, LocalTime time) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = monthDay(local.toLocalDate(), dayOfMonth).atTime(time).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = monthDay(local.toLocalDate().plusMonths(1), dayOfMonth).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    /** Ostatnie wystąpienie dnia miesiąca o danej godzinie nie później niż {@code now}. */
    public static Instant previousMonthly(Instant now, ZoneId zone, int dayOfMonth, LocalTime time) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = monthDay(local.toLocalDate(), dayOfMonth).atTime(time).atZone(zone);
        if (candidate.toInstant().isAfter(now)) {
            candidate = monthDay(local.toLocalDate().minusMonths(1), dayOfMonth).atTime(time).atZone(zone);
        }
        return candidate.toInstant();
    }

    /** Początek bieżącej doby (00:00) — okno dziennych limitów questów. */
    public static Instant dayStart(Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    /** Początek bieżącego tygodnia (poniedziałek 00:00) — okno limitu KC z questów. */
    public static Instant weekStart(Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant();
    }

    private static LocalDate monthDay(LocalDate anyDayInMonth, int dayOfMonth) {
        int last = anyDayInMonth.lengthOfMonth();
        return anyDayInMonth.withDayOfMonth(Math.min(dayOfMonth, last));
    }
}
