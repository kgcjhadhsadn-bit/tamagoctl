package pl.kudlacze.core.util;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulesTest {

    private static final ZoneId WAW = ZoneId.of("Europe/Warsaw");
    private static final LocalTime SMOK = LocalTime.of(20, 0);
    private static final LocalTime RESET = LocalTime.of(12, 0);

    @Test
    void nextSundayEveningFromMidweek() {
        // czwartek 24.09.2026 10:00 czasu letniego (UTC+2) → niedziela 27.09 20:00 = 18:00 UTC
        assertEquals(Instant.parse("2026-09-27T18:00:00Z"),
                Schedules.nextWeekly(Instant.parse("2026-09-24T08:00:00Z"), WAW, DayOfWeek.SUNDAY, SMOK));
    }

    @Test
    void sundayBeforeAndAfterTheFight() {
        assertEquals(Instant.parse("2026-09-27T18:00:00Z"),
                Schedules.nextWeekly(Instant.parse("2026-09-27T17:59:59Z"), WAW, DayOfWeek.SUNDAY, SMOK));
        // dokładnie o 20:00 i później — już następny tydzień
        assertEquals(Instant.parse("2026-10-04T18:00:00Z"),
                Schedules.nextWeekly(Instant.parse("2026-09-27T18:00:00Z"), WAW, DayOfWeek.SUNDAY, SMOK));
    }

    @Test
    void weeklyAcrossDaylightSavingEnd() {
        // 25.10.2026 kończy się czas letni: niedziela 20:00 to wtedy 19:00 UTC
        assertEquals(Instant.parse("2026-10-25T19:00:00Z"),
                Schedules.nextWeekly(Instant.parse("2026-10-20T12:00:00Z"), WAW, DayOfWeek.SUNDAY, SMOK));
    }

    @Test
    void monthlyResetOnTheFirstAtNoon() {
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"),
                Schedules.nextMonthly(Instant.parse("2026-09-25T20:00:00Z"), WAW, 1, RESET));
        // po resecie 1.10 12:00 — następny 1.11 (czas zimowy: 11:00 UTC)
        assertEquals(Instant.parse("2026-11-01T11:00:00Z"),
                Schedules.nextMonthly(Instant.parse("2026-10-01T10:00:00Z"), WAW, 1, RESET));
        // przełom roku
        assertEquals(Instant.parse("2027-01-01T11:00:00Z"),
                Schedules.nextMonthly(Instant.parse("2026-12-15T11:00:00Z"), WAW, 1, RESET));
    }

    @Test
    void previousMonthlyDetectsMissedReset() {
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"),
                Schedules.previousMonthly(Instant.parse("2026-09-25T20:00:00Z"), WAW, 1, RESET));
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"),
                Schedules.previousMonthly(Instant.parse("2026-10-01T10:00:00Z"), WAW, 1, RESET));
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"),
                Schedules.previousMonthly(Instant.parse("2026-10-01T09:59:59Z"), WAW, 1, RESET));
    }

    @Test
    void dayOfMonthIsClampedToMonthLength() {
        // 31. dzień w lutym → 28.02
        assertEquals(Instant.parse("2027-02-28T11:00:00Z"),
                Schedules.nextMonthly(Instant.parse("2027-02-10T00:00:00Z"), WAW, 31, RESET));
    }

    @Test
    void weekAndDayStartInWarsaw() {
        // niedziela 27.09 23:30 w Warszawie (21:30 UTC) — tydzień zaczął się w poniedziałek 21.09 00:00 (20.09 22:00 UTC)
        Instant sundayNight = Instant.parse("2026-09-27T21:30:00Z");
        assertEquals(Instant.parse("2026-09-20T22:00:00Z"), Schedules.weekStart(sundayNight, WAW));
        assertEquals(Instant.parse("2026-09-26T22:00:00Z"), Schedules.dayStart(sundayNight, WAW));
        // 00:30 w poniedziałek w Warszawie to już nowy tydzień
        assertEquals(Instant.parse("2026-09-27T22:00:00Z"), Schedules.weekStart(Instant.parse("2026-09-27T22:30:00Z"), WAW));
    }

    @Test
    void dailyRestartAtFive() {
        LocalTime five = LocalTime.of(5, 0);
        // 25.09 23:00 w Warszawie → 26.09 05:00 (03:00 UTC)
        assertEquals(Instant.parse("2026-09-26T03:00:00Z"), Schedules.nextDaily(Instant.parse("2026-09-25T21:00:00Z"), WAW, five));
        assertEquals(Instant.parse("2026-09-27T03:00:00Z"), Schedules.nextDaily(Instant.parse("2026-09-26T03:00:00Z"), WAW, five));
        // noc zmiany czasu (25.10): 05:00 czasu zimowego = 04:00 UTC
        assertEquals(Instant.parse("2026-10-25T04:00:00Z"), Schedules.nextDaily(Instant.parse("2026-10-24T22:00:00Z"), WAW, five));
    }
}
