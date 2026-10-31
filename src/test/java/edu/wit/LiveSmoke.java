package edu.wit;

import com.google.transit.realtime.GtfsRealtime;
import java.nio.file.Path;
import java.time.*;

/** Bounded live smoke test; output is isolated from the research dataset. */
public class LiveSmoke {
    public static void main(String[] args) throws Exception {
        if (!ServiceWindow.isOpen(Instant.now())) throw new IllegalStateException("Run during service hours");
        Instant end = Instant.now().plus(Duration.ofMinutes(args.length == 0 ? 15 : Integer.parseInt(args[0])));
        Instant nextBikes = Instant.MIN;
        var info = BluebikesOrangeLineCapacity.loadStations(FeedClient.fetch(BluebikesOrangeLineCapacity.STATION_INFO_URL));
        SegmentHeadwayTracker tracker = new SegmentHeadwayTracker();
        int arrivals = 0, bikeRows = 0;
        Path dir = Path.of("target", "smoke", String.valueOf(System.currentTimeMillis()));
        try (DailyCsv trains = new DailyCsv(dir, "arrivals", "timestamp,stop_id,trip_id,vehicle_id,direction");
             DailyCsv bikes = new DailyCsv(dir, "bikes", "timestamp,station_id,segment,bikes,ebikes,docks,capacity")) {
            while (Instant.now().isBefore(end) && ServiceWindow.isOpen(Instant.now())) {
                var feed = GtfsRealtime.FeedMessage.parseFrom(FeedClient.fetch(SegmentHeadwayTracker.FEED_URL));
                Instant now = Instant.now();
                arrivals += tracker.collect(feed, trains, now);
                boolean active = feed.getEntityList().stream().anyMatch(e -> e.hasVehicle()
                        && e.getVehicle().getTrip().getRouteId().equals("Orange"));
                if (active && !now.isBefore(nextBikes)) {
                    bikeRows += BluebikesOrangeLineCapacity.collect(FeedClient.fetch(
                            BluebikesOrangeLineCapacity.STATION_STATUS_URL), info, bikes, now);
                    nextBikes = now.plusSeconds(300);
                }
                System.out.println(ServiceWindow.timestamp(now) + " smoke totals: arrivals=" + arrivals + ", bike rows=" + bikeRows);
                Thread.sleep(30_000);
            }
        }
        System.out.println("Smoke complete; inspect " + dir);
    }
}
