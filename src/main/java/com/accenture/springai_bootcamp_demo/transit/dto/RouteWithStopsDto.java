package com.accenture.springai_bootcamp_demo.transit.dto;

import java.util.List;

public record RouteWithStopsDto(
        String routeNum,
        String routeName,
        String transport,
        List<String> stopIds
) {}
