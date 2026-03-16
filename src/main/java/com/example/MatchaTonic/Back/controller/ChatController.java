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
     * [CHAT-01] 실시간 메시지 송수신 (WebSocket)
     * 프론트엔드 발신 경로: /pub/chat/message
     */
    @MessageMapping("/chat/message")
    public void message(ChatMessageDto message) {
        // [수정] 로그에 senderEmail을 출력하도록 변경 (디버깅용)
        log.info("채팅 수신: ProjectId={}, SenderEmail={}, Message={}",
                message.getProjectId(), message.getSenderEmail(), message.getMessage());

        if (ChatMessageDto.MessageType.ENTER.equals(message.getType())) {
            // 입장 메시지 생성
            ChatMessageDto enterMessage = ChatMessageDto.builder()
                    .type(message.getType())
                    .projectId(message.getProjectId())
                    .senderEmail(message.getSenderEmail())
                    .senderName(message.getSenderName())
                    .message(message.getSenderName() + "님이 입장하셨습니다.")
                    .build();

            chatService.saveAndSendMessage(enterMessage);
        } else {
            // 일반 대화(TALK) 및 시스템(SYSTEM) 메시지 처리
            chatService.saveAndSendMessage(message);
        }
    }

    /**
     * [CHAT-02] 프로젝트 진입 시점 처리
     * 프론트엔드 발신 경로: /pub/chat/enter
     */
    @MessageMapping("/chat/enter")
    public void enter(ChatMessageDto message) {
        log.info("프로젝트 입장(AI 체크): ProjectId={}", message.getProjectId());
        chatService.checkSubjectAndInitiateAI(message.getProjectId());
    }

    /**
     * [CHAT-03] 과거 채팅 내역 조회 (HTTP GET)
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