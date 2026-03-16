package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Chat", description = "실시간 채팅 및 내역 조회 API")
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * [CHAT-01] 실시간 메시지 송수신 (WebSocket)
     * 프론트엔드 발행 경로: /pub/chat/message
     */
    @MessageMapping("/chat/message")
    public void message(ChatMessageDto message) {
        log.info("WebSocket 메시지 수신: ProjectId={}, SenderEmail={}, Message={}",
                message.getProjectId(), message.getSenderEmail(), message.getMessage());
        chatService.saveAndSendMessage(message);
    }

    /**
     * [CHAT-02] 프로젝트 진입 시점 처리 (AI 인사말 체크)
     * 프론트엔드 발행 경로: /pub/chat/enter
     */
    @MessageMapping("/chat/enter")
    public void enter(ChatMessageDto message) {
        log.info("프로젝트 입장(AI 체크): ProjectId={}", message.getProjectId());
        chatService.checkSubjectAndInitiateAI(message.getProjectId());
    }

    /**
     * [CHAT-03] 과거 채팅 내역 조회
     */
    @Operation(summary = "과거 채팅 내역 조회")
    @GetMapping("/api/chat/{projectId}/messages")
    public ResponseEntity<List<ChatMessageDto>> getChatMessages(@PathVariable("projectId") Long projectId) {
        log.info("과거 내역 조회 요청: ProjectId={}", projectId);
        List<ChatMessageDto> messages = chatService.getChatMessages(projectId);
        return ResponseEntity.ok(messages);
    }

    /**
     * [CHAT-04] 메시지 전송 폴백 (REST API)
     * 프론트엔드가 STOMP 연결 실패 시 호출하는 경로
     */
    @Operation(summary = "메시지 전송 폴백 (REST)")
    @PostMapping("/api/chat/{projectId}/messages")
    public ResponseEntity<Void> sendMessageFallback(
            @PathVariable("projectId") Long projectId,
            @RequestBody ChatMessageDto dto) {
        log.info("REST 폴백 메시지 수신: ProjectId={}, Message={}", projectId, dto.getMessage());
        dto.setProjectId(projectId);
        chatService.saveAndSendMessage(dto);
        return ResponseEntity.ok().build();
    }
}