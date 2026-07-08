package com.accenture.springai_bootcamp_demo.service;

import com.accenture.springai_bootcamp_demo.client.OllamaClient;
import com.accenture.springai_bootcamp_demo.dto.ChatDto;
import com.accenture.springai_bootcamp_demo.dto.ChatSummaryDto;
import com.accenture.springai_bootcamp_demo.dto.CreateChatRequest;
import com.accenture.springai_bootcamp_demo.dto.SendMessageRequest;
import com.accenture.springai_bootcamp_demo.entity.Chat;
import com.accenture.springai_bootcamp_demo.entity.ChatMessage;
import com.accenture.springai_bootcamp_demo.entity.Role;
import com.accenture.springai_bootcamp_demo.mapper.ChatMapper;
import com.accenture.springai_bootcamp_demo.repository.ChatRepository;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates chat use-cases: creating, reading and deleting conversations,
 * and exchanging chatMessages with the AI model. The public methods read as a
 * high-level script; persistence and mapping details live in private helpers.
 */
@Slf4j
@Service
@AllArgsConstructor
public class ChatService {
    private final ChatRepository chatRepository;
    private final OllamaClient ollamaClient;
    private final ChatMapper chatMapper;

    @Transactional
    public ChatDto createChat(CreateChatRequest request) {
        Chat chat = Chat.create(ChatTitles.resolveInitial(request.title()));
        chatRepository.save(chat);
        log.info("Created chat {}", chat.getId());
        return chatMapper.toDto(chat);
    }

    @Transactional(readOnly = true)
    public List<ChatSummaryDto> listChats() {
        return chatMapper.toSummaries(chatRepository.findAllByOrderByUpdatedAtDesc());
    }

    @Transactional(readOnly = true)
    public ChatDto getChat(String chatId) {
        return chatMapper.toDto(loadChat(chatId));
    }

    @Transactional
    public void deleteChat(String chatId) {
        if (!chatRepository.existsById(chatId)) {
            throw new ChatNotFoundException(chatId);
        }
        chatRepository.deleteById(chatId);
        log.info("Deleted chat {}", chatId);
    }

    /**
     * Persists the user message, asks the model for a reply, stores it and
     * returns the refreshed conversation.
     */
    @Transactional
    public ChatDto sendMessage(String chatId, SendMessageRequest request) {
        Chat chat = loadChat(chatId);

        recordUserMessage(chat, request.content());
        String reply = ollamaClient.complete(chat.getChatMessages());
        recordAssistantMessage(chat, reply);

        chatRepository.save(chat);
        return chatMapper.toDto(chat);
    }

    private void recordUserMessage(Chat chat, String content) {
        chat.addMessage(ChatMessage.of(Role.USER, content));
        if (ChatTitles.isPlaceholder(chat.getTitle())) {
            chat.setTitle(ChatTitles.fromFirstMessage(content));
        }
    }

    private void recordAssistantMessage(Chat chat, String content) {
        chat.addMessage(ChatMessage.of(Role.ASSISTANT, content));
    }

    private Chat loadChat(String chatId) {
        return chatRepository.findWithMessagesById(chatId)
                .orElseThrow(() -> new ChatNotFoundException(chatId));
    }
}
