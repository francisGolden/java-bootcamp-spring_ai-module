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

    private final RigasSatiksmeClient client;
    private final GtfsDataService gtfsDataService;

    public TransitTools(RigasSatiksmeClient client, GtfsDataService gtfsDataService) {
        this.client = client;
        this.gtfsDataService = gtfsDataService;
    }

    @Tool(description = "Returns the list of all Riga public transport lines " +
            "(bus, tram, trolleybus), including their short name and full name. " +
            "Use this tool when the user asks which lines exist or for information about a specific line.")
    public List<RouteDto> getRoutes() {
        log.info("Tool call: getRoutes()");
        return client.getRoutes();
    }


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