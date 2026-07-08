package com.accenture.springai_bootcamp_demo.client;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final String ROUTES_URL = "https://saraksti.rigassatiksme.lv/riga/routes.txt";

    private final RestClient restClient = RestClient.builder()
            .messageConverters(converters -> converters.add(
                    new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8)))
            .build();

    // Simple in-memory cache; swap for @Cacheable/Caffeine later if needed.
    private final AtomicReference<List<RouteDto>> routesCache = new AtomicReference<>(List.of());

    public List<RouteDto> getRoutes() {
        List<RouteDto> cached = routesCache.get();
        if (cached.isEmpty()) {
            refreshRoutes();
        }
        return routesCache.get();
    }

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
            throw new RuntimeException("Failed to reach Rigas Satiksme routes feed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Minimal CSV parser for GTFS routes.txt. GTFS files are plain CSV with
     * a header row; this handles the common case (no embedded commas in
     * quoted fields). Swap for a proper CSV library (e.g. commons-csv) if
     * fields ever contain commas.
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

    private String field(String[] fields, Integer idx) {
        if (idx == null || idx >= fields.length) {
            return "";
        }
        return fields[idx].trim();
    }

}