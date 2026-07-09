package com.accenture.springai_bootcamp_demo.transit;
import java.util.List;

import com.accenture.springai_bootcamp_demo.transit.client.RigasSatiksmeClient;
import com.accenture.springai_bootcamp_demo.transit.dto.Route;
import com.accenture.springai_bootcamp_demo.transit.dto.RouteDto;
import com.accenture.springai_bootcamp_demo.transit.service.GtfsDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tools exposed to the LLM for answering questions about Riga's public
 * transport network (Rigas Satiksme).
 */
@Slf4j
@Component
public class TransitTools {

    /**
     * The client used to fetch high-level route data directly from the Rīgas Satiksme API.
     */
    private final RigasSatiksmeClient client;

    /**
     * A service that manages the in-memory GTFS database and executes complex routing algorithms.
     */
    private final GtfsDataService gtfsDataService;

    /**
     * Constructs the TransitTools component with its required dependencies.
     * Spring automatically injects the client and service beans when creating this instance.
     *
     * @param client          The client for fetching static route lists.
     * @param gtfsDataService The service handling GTFS data and routing logic.
     */
    public TransitTools(RigasSatiksmeClient client, GtfsDataService gtfsDataService) {
        this.client = client;
        this.gtfsDataService = gtfsDataService;
    }

    /**
     * Retrieves a list of all public transport routes.
     * * The @Tool annotation registers this method as a callable function for the LLM.
     * The description provided in the annotation acts as an explicit prompt constraint,
     * instructing the AI on exactly when and how to decide to invoke this method.
     *
     * @return A list of RouteDto objects representing the available transit lines.
     */
    @Tool(description = "Returns the list of all Riga public transport lines " +
            "(bus, tram, trolleybus), including their short name and full name. " +
            "Use this tool when the user asks which lines exist or for information about a specific line.")
    public List<RouteDto> getRoutes() {
        log.info("Tool call: getRoutes()");
        return client.getRoutes();
    }

    /**
     * Calculates direct public transport routes between two specified stops.
     * * Like getRoutes(), this is exposed to the AI via the @Tool annotation. The AI parses the
     * user's natural language request, extracts the origin and destination stop names,
     * and passes them as arguments to this method.
     *
     * @param originStopName      The starting location provided by the user.
     * @param destinationStopName The destination location provided by the user.
     * @return A list of direct Route objects connecting the two stops, or an empty list if none exist.
     */
    @Tool(description = "Finds direct public transport routes (no transfers) between two stop names in Riga. " +
            "Use this when the user asks how to get from one place to another by bus, tram, or trolleybus. " +
            "Pass the stop names as written by the user, in a single call.")
    public List<Route> findDirectRoutes(String originStopName, String destinationStopName) {
        log.info("Tool call: findDirectRoutes(origin={}, destination={})", originStopName, destinationStopName);

        List<Route> result = gtfsDataService.findDirectRoutesByNames(originStopName, destinationStopName);

        log.info("Found {} direct routes between '{}' and '{}'", result.size(), originStopName, destinationStopName);
        return result;
    }
}