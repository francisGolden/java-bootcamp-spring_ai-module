package com.accenture.springai_bootcamp_demo.transit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.text.Normalizer;

import com.accenture.springai_bootcamp_demo.transit.dto.Route;
import com.accenture.springai_bootcamp_demo.transit.dto.Stop;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class GtfsDataService {

    private static final String GTFS_ZIP_URL = "https://saraksti.rigassatiksme.lv/gtfs.zip";

    private final RestClient restClient = RestClient.create();

    // stop_id -> Stop
    private Map<String, Stop> stopsById = Map.of();
    // route_id -> Route
    private Map<String, Route> routesById = Map.of();
    // trip_id -> route_id
    private Map<String, String> tripToRoute = Map.of();
    // trip_id -> ordered list of stop_ids (by stop_sequence)
    private Map<String, List<String>> tripStopSequence = Map.of();

    @PostConstruct
    public void loadGtfsData() {
        try {
            byte[] zipBytes = restClient.get()
                    .uri(GTFS_ZIP_URL)
                    .retrieve()
                    .body(byte[].class);

            Map<String, String> files = unzip(zipBytes);

            stopsById = parseStops(files.get("stops.txt"));
            routesById = parseRoutes(files.get("routes.txt"));
            tripToRoute = parseTripToRoute(files.get("trips.txt"));
            tripStopSequence = parseStopTimes(files.get("stop_times.txt"));

            log.info("GTFS loaded: {} stops, {} routes, {} trips",
                    stopsById.size(), routesById.size(), tripToRoute.size());
        } catch (Exception ex) {
            log.error("Failed to load GTFS feed", ex);
            throw new RuntimeException("Failed to load GTFS feed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, String> unzip(byte[] zipBytes) throws IOException {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zis.transferTo(out);
                String content = out.toString(StandardCharsets.UTF_8);
                files.put(entry.getName(), stripBom(content));
            }
        }
        return files;
    }

    private String stripBom(String content) {
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            return content.substring(1);
        }
        return content;
    }

    private Map<String, Stop> parseStops(String csv) throws IOException {
        Map<String, Stop> result = new HashMap<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv))) {
            for (CSVRecord r : parser) {
                String id = r.get("stop_id");
                result.put(id, new Stop(id, r.get("stop_name")));
            }
        }
        return result;
    }

    private Map<String, Route> parseRoutes(String csv) throws IOException {
        Map<String, Route> result = new HashMap<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv))) {
            for (CSVRecord r : parser) {
                String id = r.get("route_id");
                result.put(id, new Route(
                        id,
                        r.isSet("route_short_name") ? r.get("route_short_name") : "",
                        r.isSet("route_long_name") ? r.get("route_long_name") : "",
                        r.isSet("route_type") ? r.get("route_type") : ""
                ));
            }
        }
        return result;
    }

    private Map<String, String> parseTripToRoute(String csv) throws IOException {
        Map<String, String> result = new HashMap<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv))) {
            for (CSVRecord r : parser) {
                result.put(r.get("trip_id"), r.get("route_id"));
            }
        }
        return result;
    }

    private Map<String, List<String>> parseStopTimes(String csv) throws IOException {
        // trip_id -> list of (stop_sequence, stop_id), then sorted
        Map<String, List<int[]>> raw = new HashMap<>(); // placeholder not used; simpler below
        Map<String, TreeMap<Integer, String>> sequenced = new HashMap<>();

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv))) {
            for (CSVRecord r : parser) {
                String tripId = r.get("trip_id");
                int seq = Integer.parseInt(r.get("stop_sequence"));
                String stopId = r.get("stop_id");
                sequenced.computeIfAbsent(tripId, k -> new TreeMap<>()).put(seq, stopId);
            }
        }

        Map<String, List<String>> result = new HashMap<>();
        sequenced.forEach((tripId, seqMap) -> result.put(tripId, new ArrayList<>(seqMap.values())));
        return result;
    }

    // --- Public read API used by the tool ---

    public List<Stop> findStopsByName(String name) {
        String normalizedQuery = normalize(name);
        String[] queryWords = normalizedQuery.split("\\s+");

        return stopsById.values().stream()
                .filter(s -> fuzzyMatch(queryWords, normalize(s.stopName())))
                .toList();
    }

    public List<Route> findDirectRoutesByNames(String originName, String destinationName) {
        List<Stop> originCandidates = findStopsByName(originName);
        List<Stop> destinationCandidates = findStopsByName(destinationName);

        if (originCandidates.isEmpty() || destinationCandidates.isEmpty()) {
            return List.of();
        }

        Set<Route> result = new LinkedHashSet<>();
        for (Stop origin : originCandidates) {
            for (Stop destination : destinationCandidates) {
                result.addAll(findDirectRoutes(origin.stopId(), destination.stopId()));
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Matches tolerating Latvian noun declension (e.g. "Agenskalns" vs "Agenskalna"):
     * every word in the query must share a prefix (first 5 chars, or full word if shorter)
     * with at least one word in the candidate stop name.
     */
    private boolean fuzzyMatch(String[] queryWords, String candidateNormalized) {
        String[] candidateWords = candidateNormalized.split("\\s+");

        for (String queryWord : queryWords) {
            boolean found = Arrays.stream(candidateWords)
                    .anyMatch(cw -> levenshteinDistance(
                            truncate(queryWord, 6), truncate(cw, 6)) <= 1);
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    public List<Route> findDirectRoutes(String originStopId, String destinationStopId) {
        Set<String> matchingRouteIds = new LinkedHashSet<>();

        for (var entry : tripStopSequence.entrySet()) {
            List<String> sequence = entry.getValue();

            int originIdx = sequence.indexOf(originStopId);
            int destIdx = sequence.indexOf(destinationStopId);

            if (originIdx != -1 && destIdx != -1 && originIdx < destIdx) {
                String routeId = tripToRoute.get(entry.getKey());
                if (routeId != null) {
                    matchingRouteIds.add(routeId);
                }
            }
        }

        return matchingRouteIds.stream()
                .map(routesById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static final Map<Character, Character> CHAR_FALLBACK_MAP = Map.ofEntries(
            Map.entry('ł', 'l'), Map.entry('Ł', 'L'),
            Map.entry('đ', 'd'), Map.entry('Đ', 'D'),
            Map.entry('ø', 'o'), Map.entry('Ø', 'O'),
            Map.entry('å', 'a'), Map.entry('Å', 'A'),
            Map.entry('æ', 'a'), Map.entry('Æ', 'A'),
            Map.entry('ß', 's')
    );

    private String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        StringBuilder sb = new StringBuilder();
        for (char c : noAccents.toCharArray()) {
            sb.append(CHAR_FALLBACK_MAP.getOrDefault(c, c));
        }
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }
}
