package pl.kudlacze.core.util;

import java.time.Duration;

/** Formatowanie czasu po polsku (np. „2 dni 3 godz.”, „5 min 10 s”). */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String duration(Duration d) {
        long total = Math.max(0, d.getSeconds());
        long days = total / 86400;
        long hours = (total % 86400) / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(days == 1 ? " dzień " : " dni ");
        }
        if (hours > 0) {
            sb.append(hours).append(" godz. ");
        }
        if (minutes > 0 && days == 0) {
            sb.append(minutes).append(" min ");
        }
        if (seconds > 0 && days == 0 && hours == 0) {
            sb.append(seconds).append(" s ");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "0 s" : out;
    }

    /** Poprawna forma słowa „Kłak” dla liczby (1 Kłak, 2 Kłaki, 5 Kłaków). */
    public static String klaki(long n) {
        long abs = Math.abs(n);
        if (abs == 1) {
            return "Kłak";
        }
        long mod10 = abs % 10;
        long mod100 = abs % 100;
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "Kłaki";
        }
        return "Kłaków";
    }
}
