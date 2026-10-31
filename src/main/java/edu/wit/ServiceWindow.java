package edu.wit;
import java.time.*;
import java.time.format.DateTimeFormatter;

public final class ServiceWindow {
    public static final ZoneId ZONE = ZoneId.of("America/New_York");
    public static final LocalTime START = LocalTime.of(4, 45), END = LocalTime.of(1, 30);
    private ServiceWindow() {}
    public static boolean isOpen(Instant now) {
        LocalTime t = now.atZone(ZONE).toLocalTime();
        return !t.isBefore(START) || t.isBefore(END);
    }
    static Duration untilOpen(Instant now) {
        if (isOpen(now)) return Duration.ZERO;
        return Duration.between(now, now.atZone(ZONE).toLocalDate().atTime(START).atZone(ZONE).toInstant());
    }
    public static void awaitOpen() throws InterruptedException {
        while (!isOpen(Instant.now())) {
            System.out.println("Outside service hours; sleeping until 04:45 Eastern");
            Thread.sleep(Math.max(1, untilOpen(Instant.now()).toMillis()));
        }
    }
    public static String timestamp(Instant now) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(ZONE));
    }
    static LocalDate serviceDate(Instant now) {
        var local = now.atZone(ZONE);
        return local.toLocalTime().isBefore(START) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }
}
