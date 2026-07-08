package com.accenture.springai_bootcamp_demo.transit;
import java.util.List;

import com.accenture.springai_bootcamp_demo.client.RigasSatiksmeClient;
import com.accenture.springai_bootcamp_demo.dto.RouteDto;
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

    public TransitTools(RigasSatiksmeClient client) {
        this.client = client;
    }

    @Tool(description = "Returns the list of all Riga public transport lines " +
            "(bus, tram, trolleybus), including their short name and full name. " +
            "Use this tool when the user asks which lines exist or for information about a specific line.")
    public List<RouteDto> getRoutes() {
        log.info("Tool call: getRoutes()");
        return client.getRoutes();
    }
}