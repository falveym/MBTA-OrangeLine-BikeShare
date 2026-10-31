package edu.wit;
import com.google.transit.realtime.GtfsRealtime;
import java.io.IOException;
import java.time.*;
import java.util.*;

public class SegmentHeadwayTracker {
    static final String FEED_URL = "https://cdn.mbta.com/realtime/VehiclePositions.pb";
    private final Map<String, Instant> seen = new HashMap<>();
    private LocalDate serviceDate;
    int collect(GtfsRealtime.FeedMessage feed, DailyCsv csv, Instant now) throws IOException {
        LocalDate today = ServiceWindow.serviceDate(now);
        if (!today.equals(serviceDate)) {
            seen.clear();
            serviceDate = today;
        }
        int count = 0;
        for (var entity : feed.getEntityList()) {
            if (!entity.hasVehicle()) continue;
            var v = entity.getVehicle();
            if (!v.hasTrip() || !v.getTrip().getRouteId().equals("Orange")
                    || !v.getTrip().hasTripId() || !v.hasCurrentStatus()
                    || v.getCurrentStatus() != GtfsRealtime.VehiclePosition.VehicleStopStatus.STOPPED_AT
                    || !Set.of("70030", "70010").contains(v.getStopId())) continue;
            String key = v.getTrip().getTripId() + ":" + v.getStopId();
            if (seen.containsKey(key)) continue;
            Instant observed = v.hasTimestamp() ? Instant.ofEpochSecond(v.getTimestamp()) : now;
            if (observed.isBefore(now.minus(Duration.ofMinutes(5))) || observed.isAfter(now.plusSeconds(60))) continue;
            csv.write(observed, ServiceWindow.timestamp(observed), v.getStopId(), v.getTrip().getTripId(),
                    v.getVehicle().getId(), v.getTrip().hasDirectionId() ? v.getTrip().getDirectionId() : "");
            seen.put(key, now);
            count++;
        }
        return count;
    }
    public static void main(String[] args) throws Exception {
        SegmentHeadwayTracker tracker = new SegmentHeadwayTracker();
        try (DailyCsv csv = new DailyCsv("arrivals", "timestamp,stop_id,trip_id,vehicle_id,direction")) {
            Runtime.getRuntime().addShutdownHook(new Thread(csv::close));
            while (!Thread.currentThread().isInterrupted()) {
                ServiceWindow.awaitOpen();
                try {
                    var feed = GtfsRealtime.FeedMessage.parseFrom(FeedClient.fetch(FEED_URL));
                    Instant now = Instant.now();
                    if (ServiceWindow.isOpen(now)) System.out.println("Arrivals written: " + tracker.collect(feed, csv, now));
                } catch (IOException e) { System.err.println("Arrival poll failed: " + e.getMessage()); }
                Thread.sleep(30_000);
            }
        }
    }
}
