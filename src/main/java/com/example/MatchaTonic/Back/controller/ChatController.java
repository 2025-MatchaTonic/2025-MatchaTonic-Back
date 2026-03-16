package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Slf4j
@Tag(name = "Chat", description = "실시간 채팅 및 내역 조회 API")
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * [CHAT-02] 실시간 메시지 송수신 (WebSocket)
     * 프론트엔드 발신 경로: /pub/chat/message
     */
    @MessageMapping("/chat/message")
    public void message(ChatMessageDto message) {
        log.info("채팅 메시지 수신: ProjectId={}, Sender={}", message.getProjectId(), message.getSenderName());

        ChatMessageDto finalMessage = message;

        if (ChatMessageDto.MessageType.ENTER.equals(message.getType())) {
            finalMessage = ChatMessageDto.builder()
                    .type(message.getType())
                    .projectId(message.getProjectId())
                    .senderEmail(message.getSenderEmail())
                    .senderName(message.getSenderName())
                    .message(message.getSenderName() + "님이 입장하셨습니다.")
                    .build();
        }

        // DB 저장 및 /sub/project/{projectId}로 브로드캐스팅
        chatService.saveAndSendMessage(finalMessage);
    }

    /**
     * [CHAT-04] 프로젝트 진입 시점 처리 (AI 인사말 등)
     * 프론트엔드 발신 경로: /pub/chat/enter
     */
    @MessageMapping("/chat/enter")
    public void enter(ChatMessageDto message) {
        log.info("프로젝트 입장 이벤트: ProjectId={}", message.getProjectId());
        chatService.checkSubjectAndInitiateAI(message.getProjectId());
    }

    /**
     * [CHAT-02] 과거 채팅 내역 조회 (HTTP GET)
     */
    @Operation(summary = "과거 채팅 내역 조회")
    @GetMapping("/api/chat/{projectId}/messages")
    @ResponseBody
    public ResponseEntity<List<ChatMessageDto>> getChatMessages(@PathVariable Long projectId) {
        log.info("과거 내역 조회 요청: ProjectId={}", projectId);
        List<ChatMessageDto> messages = chatService.getChatMessages(projectId);
        return ResponseEntity.ok(messages);
    }
}