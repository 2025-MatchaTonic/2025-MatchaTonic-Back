package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final SimpMessageSendingOperations messagingTemplate;

    @MessageMapping("/chat/message")
    public void message(ChatMessageDto message) {

        if (ChatMessageDto.MessageType.ENTER.equals(message.getType())) {
            message.setMessage(message.getSenderName() + "님이 입장하셨습니다.");
        }

        messagingTemplate.convertAndSend("/sub/chat/room/" + message.getProjectId(), message);
    }
}