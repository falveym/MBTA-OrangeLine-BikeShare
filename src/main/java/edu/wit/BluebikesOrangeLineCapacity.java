package edu.wit;

import java.io.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class BluebikesOrangeLineCapacity {
    static final String STATION_INFO_URL = "https://gbfs.lyft.com/gbfs/1.1/bos/en/station_information.json";
    static final String STATION_STATUS_URL = "https://gbfs.lyft.com/gbfs/1.1/bos/en/station_status.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double RADIUS_METERS = 500.0;

    static class StationPoint {
        final String name;
        final double lat;
        final double lon;
        StationPoint(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    // SOUTH ORANGE LINE REGION (Forest Hills → Ruggles)
    private static final StationPoint[] SOUTH_ORANGE = {
            new StationPoint("Forest Hills", 42.2988, -71.1131),
            new StationPoint("Green Street", 42.3106, -71.1074),
            new StationPoint("Stony Brook", 42.3173, -71.1041),
            new StationPoint("Jackson Square", 42.3233, -71.0996),
            new StationPoint("Roxbury Crossing", 42.3313, -71.0956),
            new StationPoint("Ruggles", 42.3364, -71.0892)
    };

    // NORTH ORANGE LINE REGION (Malden → Sullivan)
    private static final StationPoint[] NORTH_ORANGE = {
            new StationPoint("Malden Center", 42.4267, -71.0744),
            new StationPoint("Wellington", 42.4024, -71.0771),
            new StationPoint("Assembly", 42.3927, -71.0772),
            new StationPoint("Sullivan", 42.3840, -71.0770)
    };

    record Station(String segment, int capacity) {}
    static JsonNode stations(byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        if (root == null) throw new IOException("Empty JSON response");
        JsonNode stations = root.path("data").path("stations");
        if (!stations.isArray() || stations.isEmpty()) throw new IOException("Missing or empty station array");
        return stations;
    }
    static Map<String, Station> loadStations(byte[] bytes) throws IOException {
        Map<String, double[]> coords = new HashMap<>();
        Map<String, Integer> capacities = new HashMap<>();
        for (JsonNode st : stations(bytes)) {
            if (!st.hasNonNull("station_id") || !st.path("lat").isNumber()
                    || !st.path("lon").isNumber() || !st.path("capacity").isIntegralNumber()) {
                throw new IOException("Invalid station information");
            }
            String id = st.get("station_id").asText();
            coords.put(id, new double[]{st.get("lat").asDouble(), st.get("lon").asDouble()});
            capacities.put(id, st.get("capacity").asInt());
        }
        Set<String> south = new TreeSet<>(), north = new TreeSet<>();
        assignStationsToSegments(coords, south, north);
        Map<String, Station> result = new TreeMap<>();
        for (String id : south) result.put(id, new Station("south", capacities.get(id)));
        for (String id : north) result.put(id, new Station("north", capacities.get(id)));
        if (south.isEmpty() || north.isEmpty()) throw new IOException("A segment has no matching stations");
        System.out.println("South station IDs: " + south + "; North station IDs: " + north);
        return result;
    }
    static int collect(byte[] bytes, Map<String, Station> info, DailyCsv csv, Instant now) throws IOException {
        // Validate the whole response before writing any rows.
        List<Object[]> rows = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (JsonNode st : stations(bytes)) {
            String id = st.path("station_id").asText();
            Station station = info.get(id);
            if (station == null) continue;
            if (!st.path("num_bikes_available").isIntegralNumber()
                    || !st.path("num_ebikes_available").isIntegralNumber()
                    || !st.path("num_docks_available").isIntegralNumber()) throw new IOException("Missing counts for " + id);
            int bikes = st.get("num_bikes_available").asInt(), ebikes = st.get("num_ebikes_available").asInt(),
                    docks = st.get("num_docks_available").asInt();
            if (bikes < 0 || ebikes < 0 || docks < 0) throw new IOException("Negative counts for " + id);
            if (!found.add(id)) throw new IOException("Duplicate station " + id);
            rows.add(new Object[]{ServiceWindow.timestamp(now), id, station.segment(), bikes, ebikes, docks, station.capacity()});
        }
        if (!found.containsAll(info.keySet())) throw new IOException("Status feed missing matched stations");
        for (Object[] row : rows) csv.write(now, row);
        return rows.size();
    }
    public static void main(String[] args) throws Exception {
        Map<String, Station> info = Map.of();
        LocalDate loaded = null;
        try (DailyCsv csv = new DailyCsv("bikes", "timestamp,station_id,segment,bikes,ebikes,docks,capacity")) {
            Runtime.getRuntime().addShutdownHook(new Thread(csv::close));
            while (!Thread.currentThread().isInterrupted()) {
                ServiceWindow.awaitOpen();
                try {
                    LocalDate today = Instant.now().atZone(ServiceWindow.ZONE).toLocalDate();
                    if (!today.equals(loaded)) {
                        info = loadStations(FeedClient.fetch(STATION_INFO_URL));
                        loaded = today;
                    }
                    // Do not record bike observations while the Orange Line feed has no trips.
                    var feed = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedClient.fetch(SegmentHeadwayTracker.FEED_URL));
                    boolean active = feed.getEntityList().stream().anyMatch(e -> e.hasVehicle()
                            && e.getVehicle().hasTrip() && e.getVehicle().getTrip().getRouteId().equals("Orange"));
                    if (active) {
                        byte[] status = FeedClient.fetch(STATION_STATUS_URL);
                        Instant now = Instant.now();
                        if (ServiceWindow.isOpen(now)) System.out.println("Bike rows written: " + collect(status, info, csv, now));
                    } else System.out.println("No Orange Line trips; skipping bike poll");
                } catch (IOException e) { System.err.println("Bike poll failed; no synthetic zeros: " + e.getMessage()); }
                Thread.sleep(300_000);
            }
        }
    }
    private static void assignStationsToSegments(
            Map<String, double[]> coords,
            Set<String> south, Set<String> north
    ) {
        for (Map.Entry<String, double[]> e : coords.entrySet()) {
            String id = e.getKey();
            double lat = e.getValue()[0];
            double lon = e.getValue()[1];

            for (StationPoint p : SOUTH_ORANGE) {
                if (distanceMeters(lat, lon, p.lat, p.lon) <= RADIUS_METERS) {
                    south.add(id);
                    break;
                }
            }
            for (StationPoint p : NORTH_ORANGE) {
                if (distanceMeters(lat, lon, p.lat, p.lon) <= RADIUS_METERS) {
                    north.add(id);
                    break;
                }
            }
        }
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * (2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }
}
