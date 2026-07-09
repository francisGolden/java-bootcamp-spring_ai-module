package com.accenture.springai_bootcamp_demo.transit.client;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.accenture.springai_bootcamp_demo.client.OllamaException;
import org.springframework.http.converter.StringHttpMessageConverter;
import com.accenture.springai_bootcamp_demo.transit.dto.RouteDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches and parses public GTFS data published by Rīgas Satiksme.
 * No API key required. Static data (routes) is cached in memory and
 * refreshed on demand via {@link #refreshRoutes()}.
 */
@Slf4j
@Component
public class RigasSatiksmeClient {

    /**
     * The direct URL to the static GTFS routes file provided by Rīgas Satiksme.
     * This public endpoint contains the data for all transit lines (bus, tram, trolleybus).
     */
    private static final String ROUTES_URL = "https://saraksti.rigassatiksme.lv/riga/routes.txt";

    /**
     * A synchronous HTTP client used to fetch the remote CSV data.
     * It is explicitly configured with a UTF-8 StringHttpMessageConverter to ensure
     * that Latvian characters and diacritics (e.g., ā, č, ē) in the route names are parsed correctly.
     */
    private final RestClient restClient = RestClient.builder()
            .messageConverters(converters -> converters.add(
                    new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8)))
            .build();

    // Simple in-memory cache; swap for @Cacheable/Caffeine later if needed.
    /**
     * A thread-safe, in-memory cache for the transit routes.
     * Using AtomicReference ensures that concurrent reads and writes to the cache
     * (especially during a refresh) are handled safely without explicit synchronization blocks.
     * Note: This is a simple implementation; swap for @Cacheable or Caffeine if TTL (Time To Live) eviction is needed.
     */
    private final AtomicReference<List<RouteDto>> routesCache = new AtomicReference<>(List.of());

    /**
     * Retrieves the list of public transport routes.
     * This method implements a basic lazy-loading (or cache-aside) pattern: it checks if the
     * in-memory cache is empty, and if so, triggers a fetch from the external API before returning the data.
     *
     * @return A list of RouteDto objects representing the available transit lines.
     */
    public List<RouteDto> getRoutes() {
        // Log the tool invocation to help developers trace when the AI decides to call this function.
        List<RouteDto> cached = routesCache.get();
        if (cached.isEmpty()) {
            refreshRoutes();
        }
        // Delegate the actual data retrieval to the dedicated client.
        return routesCache.get();
    }

    /**
     * Fetches the latest transit routes from the external Rīgas Satiksme API and updates the local cache.
     * This method performs a synchronous HTTP GET request to retrieve the raw GTFS CSV data,
     * parses it into domain data transfer objects (DTOs), and safely replaces the current cache state.
     *
     * @throws RuntimeException if the external API is unreachable, times out, or the data cannot be fetched.
     */
    public void refreshRoutes() {
        try {
            String body = restClient.get()
                    .uri(ROUTES_URL)
                    .retrieve()
                    .body(String.class);

            routesCache.set(parseRoutes(body));
            log.info("Loaded {} routes from Rigas Satiksme", routesCache.get().size());
        } catch (RuntimeException ex) {
            log.error("Failed to fetch routes.txt from Rigas Satiksme", ex);
            throw new OllamaException("Failed to reach Rigas Satiksme routes feed: " + ex.getMessage(), ex);
        }
    }

    /**
     * A lightweight CSV parser specifically tailored for the Rīgas Satiksme routes.txt file.
     * This method reads a semicolon-separated string, dynamically maps the column headers,
     * and extracts the relevant route information into domain DTOs.
     * * Note: This implementation assumes a simple CSV structure without embedded semicolons
     * or newlines inside quoted fields.
     *
     * @param csvBody The raw CSV content fetched from the external API.
     * @return A list of populated {@link RouteDto} objects, or an empty list if the input is invalid/empty.
     */
    private List<RouteDto> parseRoutes(String csvBody) {
        List<RouteDto> result = new ArrayList<>();
        if (csvBody == null || csvBody.isBlank()) {
            return result;
        }

        String[] lines = csvBody.split("\\r?\\n");
        if (lines.length < 2) {
            return result;
        }

        String[] headers = lines[0].split(";", -1);
        Map<String, Integer> columnIndex = new java.util.HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            columnIndex.put(headers[i].trim(), i);
        }

        Integer numIdx = columnIndex.get("RouteNum");
        Integer transportIdx = columnIndex.get("Transport");
        Integer nameIdx = columnIndex.get("RouteName");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(";", -1);

            String routeNum = field(fields, numIdx);
            if (routeNum.isBlank()) {
                // Skip malformed/header-like leftover rows without a real route number
                continue;
            }

            result.add(new RouteDto(
                    routeNum,
                    routeNum,                       // no separate short name column; RouteNum doubles as it
                    field(fields, nameIdx),
                    field(fields, transportIdx)
            ));
        }
        return result;
    }

    /**
     * Safely extracts and trims a field value from an array of CSV columns.
     * This helper method prevents common parsing errors—such as NullPointerExceptions
     * or ArrayIndexOutOfBoundsExceptions—ensuring that missing or malformed data
     * results in a safe, empty string rather than crashing the application.
     *
     * @param fields The array of string values representing a single parsed CSV row.
     * @param idx    The target index to extract (can be null if the column was not found in the header).
     * @return The trimmed string value at the specified index, or an empty string if the index is invalid.
     */
    private String field(String[] fields, Integer idx) {
        if (idx == null || idx >= fields.length) {
            return "";
        }
        return fields[idx].trim();
    }

}