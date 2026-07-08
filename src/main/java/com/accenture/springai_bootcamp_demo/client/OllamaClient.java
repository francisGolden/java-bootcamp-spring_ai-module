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

    // Inject the ChatClient.Builder auto-configured by Spring Boot
    public OllamaClient(ChatClient.Builder chatClientBuilder, TransitTools tools) {
        this.chatClient = chatClientBuilder.defaultTools(tools).build();
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

    private Message convertMessage(ChatMessage msg) {
        // NOTE: Assuming your ChatMessage class has getRole() and getContent() methods.
        // Adjust the getters if they are named differently in your entity.
        String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";

        return switch (role) {
            case "system" -> new SystemMessage(msg.getContent());
            case "assistant", "model" -> new AssistantMessage(msg.getContent());
            default -> new UserMessage(msg.getContent());
        };
    }

    private String extractContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new OllamaException("Ollama returned an empty response");
        }
        return content.trim();
    }
}
