package com.accenture.springai_bootcamp_demo.dto;

/**
 * Represents a single row from GTFS routes.txt.
 * See: https://gtfs.org/schedule/reference/#routestxt
 */
public record RouteDto (
        String routeId,
        String shortName,
        String longName,
        String routeType // 0=tram, 3=bus, 11=trolleybus (GTFS extended values vary by agency)
) {
}

