package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiChatRequestDto;
import com.example.MatchaTonic.Back.dto.AiChatResponseDto;
import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.ChatMessage;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.repository.project.ChatMessageRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final SimpMessageSendingOperations messagingTemplate;
    private final RestTemplate restTemplate;

    @Value("${external.api.fastapi.chat-url}")
    private String aiChatUrl;

    /**
     * 메시지 저장 및 브로드캐스팅
     */
    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 프론트에서 type을 안 보냈을 경우 기본 TALK로 설정
        if (dto.getType() == null) {
            dto.setType(ChatMessageDto.MessageType.TALK);
        }

        // 1. DB 저장 (ENTER 타입 제외)
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        // 2. 시간 설정 및 브로드캐스트
        if (dto.getCreatedAt() == null) {
            dto.setCreatedAt(LocalDateTime.now());
        }

        log.info("Broadcasting message to /sub/project/{}", dto.getProjectId());
        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        // [수정] 3. AI 호출 조건: TALK 타입 + 발신자가 AI 아님 + 메시지에 "@mates" 포함 시에만 호출
        boolean isTalk = ChatMessageDto.MessageType.TALK.equals(dto.getType());
        boolean isNotAi = !"ai@promate.ai".equals(dto.getSenderEmail());
        boolean hasAiMention = dto.getMessage() != null && dto.getMessage().contains("@mates");

        if (isTalk && isNotAi && hasAiMention) {
            log.info("AI 호출 조건을 만족함 (@mates 감지): 프로젝트ID={}", project.getId());
            callAiAndBroadcast(project, dto);
        } else {
            log.info("AI 호출 조건 미충족 (일반 대화 또는 호출어 없음)");
        }
    }

    private void callAiAndBroadcast(Project project, ChatMessageDto userDto) {
        try {
            List<String> recentMessages = chatMessageRepository
                    .findByProjectIdWithDetails(project.getId())
                    .stream()
                    .map(ChatMessage::getMessage)
                    .filter(msg -> msg != null && !msg.isBlank())
                    .collect(Collectors.toList());

            Map<String, String> collectedData = new HashMap<>();
            collectedData.put("title", project.getName() != null ? project.getName() : "");
            collectedData.put("goal", project.getSubject() != null ? project.getSubject() : "");

            AiChatRequestDto request = new AiChatRequestDto(
                    project.getId(), userDto.getMessage(), "CHAT", "EXPLORE",
                    collectedData, recentMessages, userDto.getMessage(), recentMessages
            );

            log.info("AI 서버 요청 전송: {}", aiChatUrl);
            AiChatResponseDto response = restTemplate.postForObject(aiChatUrl, request, AiChatResponseDto.class);

            if (response != null && response.content() != null) {
                ChatMessageDto aiDto = ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.SYSTEM)
                        .projectId(project.getId())
                        .senderName("Promate AI")
                        .senderEmail("ai@promate.ai")
                        .message(response.content())
                        .createdAt(LocalDateTime.now())
                        .build();

                saveToDb(aiDto, project);
                messagingTemplate.convertAndSend("/sub/project/" + project.getId(), aiDto);
            }
        } catch (Exception e) {
            log.error("AI 응답 처리 중 오류 발생: {}", e.getMessage());
        }
    }

    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;
        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank()) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

        // Enum 매핑 안전하게 처리
        ChatMessage.MessageType entityType = ChatMessage.MessageType.TALK;
        if (dto.getType() != null) {
            try {
                entityType = ChatMessage.MessageType.valueOf(dto.getType().name());
            } catch (Exception e) {
                entityType = ChatMessage.MessageType.TALK;
            }
        }

        ChatMessage chatMessage = ChatMessage.builder()
                .project(project)
                .sender(sender)
                .message(dto.getMessage())
                .type(entityType)
                .build();

        chatMessageRepository.save(chatMessage);
    }

    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        long messageCount = chatMessageRepository.countByProjectId(projectId);

        if (messageCount == 0 && (project.getSubject() == null || project.getSubject().isEmpty())) {
            ChatMessageDto aiMsg = ChatMessageDto.builder()
                    .type(ChatMessageDto.MessageType.SYSTEM)
                    .projectId(projectId)
                    .senderName("Promate AI")
                    .senderEmail("ai@promate.ai")
                    .message("반가워요! 아직 프로젝트 주제가 정해지지 않았네요. 어떤 아이디어를 가지고 계신가요?")
                    .createdAt(LocalDateTime.now())
                    .build();

            // 입장 인사말은 "@mates" 체크 없이 즉시 전송
            saveToDb(aiMsg, project);
            messagingTemplate.convertAndSend("/sub/project/" + projectId, aiMsg);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatMessages(Long projectId) {
        return chatMessageRepository.findByProjectIdWithDetails(projectId).stream()
                .map(m -> ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.valueOf(m.getType().name()))
                        .projectId(m.getProject().getId())
                        .senderEmail(m.getSender() != null ? m.getSender().getEmail() : "ai@promate.ai")
                        .senderName(m.getSender() != null ? m.getSender().getName() : "Promate AI")
                        .message(m.getMessage())
                        .createdAt(m.getTimestamp())
                        .build())
                .collect(Collectors.toList());
    }
}