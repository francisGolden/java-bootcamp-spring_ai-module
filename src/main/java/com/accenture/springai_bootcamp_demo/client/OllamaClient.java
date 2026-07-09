package com.accenture.springai_bootcamp_demo.client;

import com.accenture.springai_bootcamp_demo.entity.ChatMessage;
import java.util.List;

import com.accenture.springai_bootcamp_demo.transit.TransitTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Thin client over the Ollama API, backed by Spring AI's
 * {@link ChatClient}. Keeps the public surface intentionally small: callers
 * hand over the conversation history and receive the assistant's reply text.
* Tool-calling: all beans passed in the constructor (typically POJOs with
* {@code @Tool}-annotated methods) are registered as default tools, so the
* model can invoke them on every request.
 */

@Slf4j
@Component
public class OllamaClient {

    private final ChatClient chatClient;

    /**
     * Constructs the OllamaClient by configuring a Spring AI ChatClient.
     * This constructor initializes the core behavior of the AI model, establishing its persona,
     * the specific tools it has access to, and its generation parameters.
     *
     * @param chatClientBuilder The auto-configured builder provided by Spring Boot.
     * @param tools The suite of custom tools (e.g., transit routing) that the model can invoke.
     */
    public OllamaClient(ChatClient.Builder chatClientBuilder, TransitTools tools) {
        this.chatClient = chatClientBuilder.defaultSystem("""
                You are a public transport assistant for Riga.
                ALWAYS use the findDirectRoutes tool to answer routing questions, 
                passing the stop names exactly as written by the user, in a single tool call.
                NEVER use external knowledge. Do not mention line numbers, 
                intermediate stops, or suggest routes that do not come DIRECTLY from the tool's output.
                
                The routeType field indicates the vehicle type based on the GTFS standard:
                0 = Tram
                3 = Bus
                11 = Trolleybus
                
                ALWAYS use the routeType value to determine the vehicle type in your response. 
                Do not guess the vehicle type based on line numbers or general knowledge.
                
                If the tool returns MORE than one direct route, you MUST list ALL of them in your response, 
                one per line, in ascending order (by route number), stating the line number and vehicle type for each. Do not randomly pick just one option; 
                the user must see all available alternatives.
                
                If the tool returns an empty list, your ONLY permitted response is to state that no direct connection 
                exists according to the data. Do not suggest alternatives or provide external links.
                """).defaultTools(tools).defaultOptions(org.springframework.ai.ollama.api.OllamaOptions.builder()
                .temperature(0.1)
                .build()).build();
    }

    public String complete(List<ChatMessage> history) {
        String reply = call(history);
        return extractContent(reply);
    }

    private String call(List<ChatMessage> history) {
        try {
            // 1. Convert your custom history into Spring AI's message structure
            List<Message> springAiMessages = toSpringAiMessages(history);

            // 2. Execute the call using ChatClient's fluent API
            return chatClient.prompt()
                    .messages(springAiMessages)
                    .call()
                    .content();

        } catch (RuntimeException ex) {
            log.error("Ollama request failed", ex);
            throw new OllamaException("Failed to reach Ollama: " + ex.getMessage(), ex);
        }
    }

    /**
     * Helper method to convert your custom ChatMessage entity
     * into the Message interface required by Spring AI.
     */
    private List<Message> toSpringAiMessages(List<ChatMessage> history) {
        return history.stream()
                .map(this::convertMessage)
                .toList();
    }

    /**
     * Converts a domain {@link ChatMessage} entity into a Spring AI {@link Message} object.
     * This mapping is necessary so that the Ollama client (Spring AI) can correctly
     * interpret the conversation history using its specific class hierarchy
     * (SystemMessage, AssistantMessage, UserMessage).
     *
     * @param msg The message saved in the database (custom entity).
     * @return A Spring AI Message instance corresponding to the role and content.
     */
    private Message convertMessage(ChatMessage msg) {
        String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";

        return switch (role) {
            case "system" -> new SystemMessage(msg.getContent());
            case "assistant", "model" -> new AssistantMessage(msg.getContent());
            default -> new UserMessage(msg.getContent());
        };
    }

    /**
     * Validates and cleans up the textual response received from the Ollama model.
     * This helper method ensures that the application does not process or return
     * empty, null, or blank messages to the user.
     *
     * @param content The raw string response generated by the AI model.
     * @return The cleaned, non-empty response string.
     * @throws OllamaException if the content is null, empty, or consists only of whitespace.
     */
    private String extractContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new OllamaException("Ollama returned an empty response");
        }
        return content.trim();
    }
}
