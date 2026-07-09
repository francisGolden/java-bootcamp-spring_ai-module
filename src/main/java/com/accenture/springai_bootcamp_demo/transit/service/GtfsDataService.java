package com.accenture.springai_bootcamp_demo.transit.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.text.Normalizer;

import com.accenture.springai_bootcamp_demo.client.OllamaException;
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

    /**
     * The direct URL to the official GTFS (General Transit Feed Specification) zip archive
     * provided by Rīgas Satiksme. This archive contains the complete schedule and spatial
     * routing data for the city's public transport network.
     */
    private static final String GTFS_ZIP_URL = "https://saraksti.rigassatiksme.lv/gtfs.zip";

    /**
     * A synchronous HTTP client used to download the binary zip file.
     * Created with default settings since no specialized message converters (like UTF-8 text)
     * are needed for fetching a raw byte stream.
     */
    private final RestClient restClient = RestClient.create();

    // stop_id -> Stop

    /* ====================================================================================
     * IN-MEMORY GTFS DATABASE
     * The following maps act as an in-memory, read-only relational database.
     * They are initialized as empty immutable maps (Map.of()) to prevent NullPointerExceptions
     * if queried before the application fully loads the GTFS data.
     * ==================================================================================== */

    /**
     * Maps a unique GTFS stop_id to its corresponding Stop domain object (containing the stop's name).
     * Used for fast lookups when translating user-provided text into actual transit stops.
     */
    private Map<String, Stop> stopsById = Map.of();

    /**
     * Maps a unique GTFS route_id to its corresponding Route domain object (bus, tram, or trolleybus).
     * Represents the high-level transit lines.
     */
    // route_id -> Route
    private Map<String, Route> routesById = Map.of();
    // trip_id -> route_id

    /**
     * Maps a unique GTFS trip_id to its parent route_id.
     * In GTFS, a "Route" is a conceptual line (e.g., Line 3), while a "Trip" is a specific
     * vehicle journey along that route at a given time or direction.
     */
    private Map<String, String> tripToRoute = Map.of();
    // trip_id -> ordered list of stop_ids (by stop_sequence)

    /**
     * Maps a unique GTFS trip_id to a chronologically ordered list of stop_ids.
     * This ordered sequence represents the exact path a vehicle takes. It is the core data structure
     * used to calculate routing: if Stop A and Stop B exist in this list, and A's index is
     * lower than B's index, a direct connection exists.
     */
    private Map<String, List<String>> tripStopSequence = Map.of();

    /**
     * Initializes the in-memory GTFS database immediately after the Spring bean is constructed.
     * The {@code @PostConstruct} annotation ensures this method runs automatically during application startup.
     * It downloads the official GTFS zip archive, extracts its contents in memory, and parses
     * the necessary CSV files to populate the queryable maps.
     * * @throws RuntimeException if the download, extraction, or parsing fails. This acts as a
     * "fail-fast" mechanism, intentionally halting the application startup
     * if the critical public transit data cannot be loaded.
     */
    @PostConstruct
    public void loadGtfsData() {
        try {
            // 1. Download: Fetch the raw binary zip archive containing the GTFS feed.
            // Retrieving the body as byte[].class allows us to handle the binary payload
            // directly in memory without needing to write temporary files to the local disk.
            byte[] zipBytes = restClient.get()
                    .uri(GTFS_ZIP_URL)
                    .retrieve()
                    .body(byte[].class);

            // 2. Extract: Unzip the binary payload into a map where keys are filenames
            // (e.g., "stops.txt") and values are the raw UTF-8 text contents of those files.
            Map<String, String> files = unzip(zipBytes);

            // 3. Parse & Populate: Process the essential GTFS files one by one.
            // Each specific parser method reads the CSV text and populates the corresponding
            // in-memory data structures (stops, routes, trips, and sequences).
            stopsById = parseStops(files.get("stops.txt"));
            routesById = parseRoutes(files.get("routes.txt"));
            tripToRoute = parseTripToRoute(files.get("trips.txt"));
            tripStopSequence = parseStopTimes(files.get("stop_times.txt"));

            // 4. Observability: Log the successful initialization and the volume of the loaded data.
            // This is crucial for operational monitoring to verify the app booted with a healthy dataset.
            log.info("GTFS loaded: {} stops, {} routes, {} trips",
                    stopsById.size(), routesById.size(), tripToRoute.size());
        } catch (Exception ex) {
            // Log the error internally with the full stack trace for developer debugging.
            log.error("Failed to load GTFS feed", ex);

            // Wrap in an unchecked exception and re-throw. In the context of @PostConstruct,
            // throwing a RuntimeException will prevent the Spring Context from initializing,
            // which prevents the application from serving requests with empty/missing routing data.
            throw new OllamaException("Failed to load GTFS feed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Extracts the contents of a ZIP archive entirely in memory.
     * This method iterates through the provided byte array, reads each file entry,
     * and converts its binary content into a UTF-8 encoded string.
     *
     * @param zipBytes The raw binary data of the ZIP archive fetched from the API.
     * @return A map where the key is the filename (e.g., "routes.txt") and the value is the extracted text content.
     * @throws IOException if an error occurs while reading the ZIP stream or its entries.
     */
    private Map<String, String> unzip(byte[] zipBytes) throws IOException {
        Map<String, String> files = new HashMap<>();

        // 1. Resource Management: Use a try-with-resources block to guarantee that the
        // ZipInputStream is properly closed, preventing memory leaks.
        // Specifying StandardCharsets.UTF_8 ensures proper encoding for filenames.
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;

            // 2. Iteration: Sequentially process each entry (file or folder) inside the zip archive.
            while ((entry = zis.getNextEntry()) != null) {
                // Skip directories; we are only interested in extracting the actual CSV text files.
                if (entry.isDirectory()) continue;

                // Create an in-memory output stream to hold the uncompressed bytes for the current file.
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                // Efficiently pipe the uncompressed data from the zip stream into our byte array buffer.
                zis.transferTo(out);

                // Convert the raw extracted bytes into a readable UTF-8 string.
                String content = out.toString(StandardCharsets.UTF_8);

                // 3. Sanitization & Storage: Strip the Byte Order Mark (BOM) if it exists
                // (as BOMs can cause issues with CSV parsers), then store the clean text in the map.
                files.put(entry.getName(), stripBom(content));
            }
        }
        return files;
    }

    /**
     * Removes the UTF-8 Byte Order Mark (BOM) from the beginning of a string if it exists.
     * Invisible BOM characters often prefix text files downloaded from Windows systems.
     * If not removed, the CSV parser will attach the BOM to the first column's header name,
     * causing lookup failures (e.g., searching for "stop_id" would fail because the key is actually "\uFEFFstop_id").
     *
     * @param content The raw string content of the file.
     * @return The sanitized string without the BOM.
     */
    private String stripBom(String content) {
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            return content.substring(1);
        }
        return content;
    }

    /**
     * Parses the GTFS stops.txt file.
     * Utilizes Apache Commons CSV to safely handle headers and quoted fields.
     *
     * @param csv The raw CSV content of stops.txt.
     * @return A map linking each stop_id to its corresponding Stop domain object.
     */
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

    /**
     * Parses the GTFS routes.txt file.
     *
     * @param csv The raw CSV content of routes.txt.
     * @return A map linking each route_id to its Route domain object.
     */
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

    /**
     * Parses the GTFS trips.txt file to establish the relationship between trips and routes.
     *
     * @param csv The raw CSV content of trips.txt.
     * @return A map linking each trip_id to its parent route_id.
     */
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

    /**
     * Parses the GTFS stop_times.txt file to build the chronological sequence of stops for every trip.
     *
     * @param csv The raw CSV content of stop_times.txt.
     * @return A map linking each trip_id to a chronologically ordered list of stop_ids.
     */
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

    /**
     * Finds all physical stops that match a given user-provided name.
     * * @param name The stop name provided by the user (e.g., "Agenskalna tirgus").
     * @return A list of Stop objects that loosely match the requested name.
     */
    public List<Stop> findStopsByName(String name) {
        String normalizedQuery = normalize(name);
        String[] queryWords = normalizedQuery.split("\\s+");

        return stopsById.values().stream()
                .filter(s -> fuzzyMatch(queryWords, normalize(s.stopName())))
                .toList();
    }

    /**
     * The primary entry point for the AI tool to calculate direct connections.
     * Because a single text name (e.g., "Centrālā stacija") might map to multiple distinct
     * GTFS stop_ids (e.g., platforms on opposite sides of the street), this method
     * cross-references all permutations of origin and destination candidates.
     *
     * @param originName      The raw name of the starting stop.
     * @param destinationName The raw name of the ending stop.
     * @return A list of unique Route objects that connect the two locations without transfers.
     */
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
            String truncatedQuery = truncate(queryWord, 8);
            int threshold = Math.max(1, truncatedQuery.length() / 3); // tollera più errori su parole più lunghe

            boolean found = Arrays.stream(candidateWords)
                    .anyMatch(cw -> levenshteinDistance(truncatedQuery, truncate(cw, 8)) <= threshold);
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Safely truncates a string to a specified maximum length.
     * This is used during fuzzy matching to compare only the prefixes of words,
     * which helps tolerate grammatical suffixes (like Latvian noun declensions).
     *
     * @param s      The original string.
     * @param maxLen The maximum allowed length.
     * @return The truncated string, or the original string if it is already shorter than maxLen.
     */
    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    /**
     * Calculates the Levenshtein distance (edit distance) between two strings.
     * This standard dynamic programming algorithm determines the minimum number of
     * single-character edits (insertions, deletions, or substitutions) required to
     * change string 'a' into string 'b'. It is crucial for tolerating user typos
     * when searching for stop names.
     *
     * @param a The first string.
     * @param b The second string.
     * @return The integer distance (0 means the strings are identical).
     */
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

    /**
     * Finds all direct transit routes (no transfers) between two specific GTFS stop IDs.
     * It scans the chronological stop sequences of all known trips to see if a vehicle
     * visits the origin stop before the destination stop.
     *
     * @param originStopId      The GTFS ID of the starting stop.
     * @param destinationStopId The GTFS ID of the destination stop.
     * @return A list of unique {@link Route} objects that connect the two stops.
     */
    public List<Route> findDirectRoutes(String originStopId, String destinationStopId) {
        // Use a LinkedHashSet to collect route IDs. This automatically prevents duplicate
        // routes from being added (e.g., if multiple trips of the same line make the connection)
        // while preserving the order in which they were found.
        Set<String> matchingRouteIds = new LinkedHashSet<>();

        for (var entry : tripStopSequence.entrySet()) {
            List<String> sequence = entry.getValue();

            // Find the zero-based index of both stops in the trip's sequence.
            int originIdx = sequence.indexOf(originStopId);
            int destIdx = sequence.indexOf(destinationStopId);

            // A valid direct connection exists ONLY IF both stops are on this trip,
            // AND the origin is visited chronologically before the destination.
            if (originIdx != -1 && destIdx != -1 && originIdx < destIdx) {
                String routeId = tripToRoute.get(entry.getKey());
                if (routeId != null) {
                    matchingRouteIds.add(routeId);
                }
            }
        }

        // Convert the matched route IDs back into rich domain Route objects.
        return matchingRouteIds.stream()
                .map(routesById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * A predefined fallback map for specific special characters that the standard
     * Java Normalizer might not handle appropriately. This is particularly useful
     * for transliterating Nordic, Slavic, and German characters commonly found in
     * Baltic contexts or tourist queries.
     */
    private static final Map<Character, Character> CHAR_FALLBACK_MAP = Map.ofEntries(
            Map.entry('ł', 'l'), Map.entry('Ł', 'L'),
            Map.entry('đ', 'd'), Map.entry('Đ', 'D'),
            Map.entry('ø', 'o'), Map.entry('Ø', 'O'),
            Map.entry('å', 'a'), Map.entry('Å', 'A'),
            Map.entry('æ', 'a'), Map.entry('Æ', 'A'),
            Map.entry('ß', 's'),
            Map.entry('ħ', 'h'), Map.entry('Ħ', 'H'),
            Map.entry('þ', "th".charAt(0)), Map.entry('ð', 'd'),
            Map.entry('œ', 'o'), Map.entry('ŧ', 't'),
            Map.entry('ı', 'i'), Map.entry('ĸ', 'k')
    );

    /**
     * Normalizes a string by stripping diacritics, applying character fallbacks,
     * and converting it to lowercase. This creates a clean, uniform string
     * suitable for robust searching and fuzzy matching.
     *
     * @param s The raw input string.
     * @return The normalized, lowercase, trimmed string.
     */
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
