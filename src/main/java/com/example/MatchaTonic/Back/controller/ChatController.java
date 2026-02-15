package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Tag(name = "Chat", description = "실시간 채팅 및 내역 조회 API")
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    // [CHAT-02] 실시간 메시지 송수신 (WebSocket)
    @MessageMapping("/chat/message")
    public void message(ChatMessageDto message) {
        // 입장 메시지 처리 로직 유지
        if (ChatMessageDto.MessageType.ENTER.equals(message.getType())) {
            message.setMessage(message.getSenderName() + "님이 입장하셨습니다.");
        }

        // DB 저장 및 실시간 브로드캐스팅은 Service에서 처리
        chatService.saveAndSendMessage(message);
    }

    //[CHAT-04] 프로젝트 진입 시점 처리 (WebSocket)
    @MessageMapping("/chat/enter")
    public void enter(ChatMessageDto message) {
        chatService.checkSubjectAndInitiateAI(message.getProjectId());
    }

    // [CHAT-02 보완] 과거 채팅 내역 조회 (HTTP GET)
    @Operation(summary = "과거 채팅 내역 조회")
    @GetMapping("/api/chat/{projectId}/messages")
    @ResponseBody // HTTP 응답을 위해 추가
    public ResponseEntity<List<ChatMessageDto>> getChatMessages(@PathVariable Long projectId) {
        List<ChatMessageDto> messages = chatService.getChatMessages(projectId);
        return ResponseEntity.ok(messages);
    }
}