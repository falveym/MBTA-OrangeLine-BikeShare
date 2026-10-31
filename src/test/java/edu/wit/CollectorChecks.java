package edu.wit;

import com.google.transit.realtime.GtfsRealtime.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Offline regression checks; no live writes or network required. */
public class CollectorChecks {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        for (String date : List.of("2026-03-08", "2026-11-01", "2026-10-31")) {
            for (String time : List.of("00:00", "01:29", "04:45", "23:59"))
                check(ServiceWindow.isOpen(LocalDateTime.parse(date + "T" + time).atZone(ServiceWindow.ZONE).toInstant()));
            for (String time : List.of("01:30", "03:00", "04:44"))
                check(!ServiceWindow.isOpen(LocalDateTime.parse(date + "T" + time).atZone(ServiceWindow.ZONE).toInstant()));
        }
        Instant now = Instant.parse("2026-10-31T16:00:00Z");
        Path dir = Files.createTempDirectory("collector-checks");
        FeedMessage feed = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
            .addEntity(FeedEntity.newBuilder().setId("1").setVehicle(VehiclePosition.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setRouteId("Orange").setTripId("trip").setDirectionId(0))
                .setStopId("70030").setCurrentStatus(VehiclePosition.VehicleStopStatus.STOPPED_AT)
                .setVehicle(VehicleDescriptor.newBuilder().setId("vehicle")))).build();
        SegmentHeadwayTracker tracker = new SegmentHeadwayTracker();
        try (DailyCsv csv = new DailyCsv(dir, "arrivals", "timestamp,stop_id,trip_id,vehicle_id,direction")) {
            check(tracker.collect(feed, csv, now) == 1);
            check(tracker.collect(feed, csv, now) == 0);
            check(tracker.collect(FeedMessage.newBuilder().setHeader(feed.getHeader()).build(), csv, now) == 0);
            check(tracker.collect(feed, csv, now.plusSeconds(86400)) == 1);
        }
        try (DailyCsv csv = new DailyCsv(dir, "arrivals", "timestamp,stop_id,trip_id,vehicle_id,direction")) {
            csv.write(now, "same day restart");
            csv.write(now.plusSeconds(86400), "next day");
        }
        byte[] bytes = Files.readAllBytes(dir.resolve("arrivals_2026-10-31.csv"));
        check(new String(bytes, StandardCharsets.UTF_8).lines().count() == 3);
        check(new String(bytes, StandardCharsets.UTF_8).indexOf('\r') < 0);
        check(Files.readAllLines(dir.resolve("arrivals_2026-11-01.csv")).size() == 3);
        check(ServiceWindow.untilOpen(Instant.parse("2026-10-31T06:00:00Z")).equals(Duration.ofMinutes(165)));
        // Moving vehicles and non-Orange routes must not create arrivals.
        var vehicle = feed.getEntity(0).getVehicle();
        for (VehiclePosition ignored : List.of(vehicle.toBuilder()
                .setCurrentStatus(VehiclePosition.VehicleStopStatus.IN_TRANSIT_TO).build(),
                vehicle.toBuilder().setTrip(vehicle.getTrip().toBuilder().setRouteId("Red")).build())) {
            var ignoredFeed = feed.toBuilder().setEntity(0, feed.getEntity(0).toBuilder().setVehicle(ignored)).build();
            try (DailyCsv csv = new DailyCsv(dir, "ignored", "header")) {
                check(new SegmentHeadwayTracker().collect(ignoredFeed, csv, now) == 0);
                check(!Files.exists(dir.resolve("ignored_2026-10-31.csv")));
            }
        }
        // Failed disk writes must not mark an arrival as already seen.
        SegmentHeadwayTracker retry = new SegmentHeadwayTracker();
        Path blocked = Files.createTempFile("collector-blocked", ".tmp");
        try (DailyCsv csv = new DailyCsv(blocked, "arrivals", "header")) {
            try { retry.collect(feed, csv, now); throw new AssertionError("Disk failure ignored"); }
            catch (java.io.IOException expected) { /* Retry with a writable directory below. */ }
        }
        try (DailyCsv csv = new DailyCsv(dir, "retry", "header")) {
            check(retry.collect(feed, csv, now) == 1);
        }
        var info = Map.of("a", new BluebikesOrangeLineCapacity.Station("south", 10));
        try (DailyCsv csv = new DailyCsv(dir, "bikes", "timestamp,station_id,segment,bikes,ebikes,docks,capacity")) {
            try {
                BluebikesOrangeLineCapacity.collect("{\"data\":{\"stations\":[{\"station_id\":\"a\"}]}}".getBytes(StandardCharsets.UTF_8), info, csv, now);
                throw new AssertionError("Missing counts accepted");
            } catch (java.io.IOException expected) { check(!Files.exists(dir.resolve("bikes_2026-10-31.csv"))); }
            byte[] valid = "{\"data\":{\"stations\":[{\"station_id\":\"a\",\"num_bikes_available\":3,\"num_ebikes_available\":1,\"num_docks_available\":4}]}}".getBytes(StandardCharsets.UTF_8);
            check(BluebikesOrangeLineCapacity.collect(valid, info, csv, now) == 1);
            check(Files.readAllLines(dir.resolve("bikes_2026-10-31.csv")).get(1).endsWith(",a,south,3,1,4,10"));
        }
        System.out.println("Offline collector checks passed: service boundaries/DST, arrival deduplication, rotation, LF, failed data.");
        if (args.length > 0) {
            var liveInfo = BluebikesOrangeLineCapacity.loadStations(Files.readAllBytes(Path.of("target/live-info.json")));
            try (DailyCsv csv = new DailyCsv(dir, "live-bikes", "timestamp,station_id,segment,bikes,ebikes,docks,capacity")) {
                System.out.println("Live bike stations resolved: " + BluebikesOrangeLineCapacity.collect(
                        Files.readAllBytes(Path.of("target/live-status.json")), liveInfo, csv, now));
            }
            FeedMessage live = FeedMessage.parseFrom(Files.readAllBytes(Path.of("target/live-vehicles.pb")));
            long orange = live.getEntityList().stream().filter(e -> e.hasVehicle()
                    && e.getVehicle().getTrip().getRouteId().equals("Orange")).count();
            check(orange > 0);
            System.out.println("Live Orange Line vehicles: " + orange);
        }
    }
}
