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

        // 1. DB 저장 (ENTER 타입은 안내 문구이므로 저장에서 제외하고 싶으면 유지, 저장하려면 if 제거)
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        // 2. 실시간 전송 (현재 시간 추가)
        if (dto.getCreatedAt() == null) {
            dto.setCreatedAt(LocalDateTime.now());
        }

        log.info("Broadcasting: senderEmail={}, message={}", dto.getSenderEmail(), dto.getMessage());
        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        // 3. 사용자의 일반 대화(TALK)인 경우에만 AI 응답 호출 (AI가 보낸 메시지에는 반응 금지)
        if (ChatMessageDto.MessageType.TALK.equals(dto.getType()) && !"ai@promate.ai".equals(dto.getSenderEmail())) {
            callAiAndBroadcast(project, dto);
        }
    }

    /**
     * AI 호출 및 응답 전송
     */
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
                    project.getId(),
                    userDto.getMessage(),
                    "CHAT",
                    "EXPLORE",
                    collectedData,
                    recentMessages,
                    userDto.getMessage(),
                    recentMessages
            );

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
            log.error("AI 응답 처리 중 오류: {}", e.getMessage());
        }
    }

    /**
     *  DTO 타입을 Entity 타입으로 안전하게 매핑하여 저장
     */
    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;
        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank()) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

        // DTO Enum -> Entity Enum 매핑 (빨간 줄 해결)
        ChatMessage.MessageType entityType;
        if (dto.getType() == ChatMessageDto.MessageType.SYSTEM) {
            entityType = ChatMessage.MessageType.SYSTEM;
        } else if (dto.getType() == ChatMessageDto.MessageType.ENTER) {
            entityType = ChatMessage.MessageType.ENTER;
        } else {
            entityType = ChatMessage.MessageType.TALK;
        }

        ChatMessage chatMessage = ChatMessage.builder()
                .project(project)
                .sender(sender)
                .message(dto.getMessage())
                .type(entityType)
                .build();

        chatMessageRepository.save(chatMessage);
        log.info("DB 저장 완료: 발신자={}, 내용={}", dto.getSenderEmail(), dto.getMessage());
    }

    /**
     * 프로젝트 입장 시 AI 인사말 로직
     */
    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 메시지 개수를 체크하여 기존 대화가 있으면 인사하지 않음
        long messageCount = chatMessageRepository.countByProjectId(projectId);
        log.info("입장 체크 - 프로젝트 ID: {}, 기존 메시지 수: {}", projectId, messageCount);

        if (messageCount == 0 && (project.getSubject() == null || project.getSubject().isEmpty())) {
            ChatMessageDto aiMsg = ChatMessageDto.builder()
                    .type(ChatMessageDto.MessageType.SYSTEM)
                    .projectId(projectId)
                    .senderName("Promate AI")
                    .senderEmail("ai@promate.ai")
                    .message("반가워요! 아직 프로젝트 주제가 정해지지 않았네요. 어떤 아이디어를 가지고 계신가요?")
                    .createdAt(LocalDateTime.now())
                    .build();

            saveAndSendMessage(aiMsg);
        }
    }

    /**
     * 과거 내역 조회
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatMessages(Long projectId) {
        return chatMessageRepository.findByProjectIdWithDetails(projectId).stream()
                .map(m -> {
                    // Entity Enum -> DTO Enum 매핑
                    ChatMessageDto.MessageType dtoType;
                    if (m.getType() == ChatMessage.MessageType.SYSTEM) {
                        dtoType = ChatMessageDto.MessageType.SYSTEM;
                    } else if (m.getType() == ChatMessage.MessageType.ENTER) {
                        dtoType = ChatMessageDto.MessageType.ENTER;
                    } else {
                        dtoType = ChatMessageDto.MessageType.TALK;
                    }

                    return ChatMessageDto.builder()
                            .type(dtoType)
                            .projectId(m.getProject().getId())
                            .senderEmail(m.getSender() != null ? m.getSender().getEmail() : "ai@promate.ai")
                            .senderName(m.getSender() != null ? m.getSender().getName() : "Promate AI")
                            .message(m.getMessage())
                            .createdAt(m.getTimestamp())
                            .build();
                })
                .collect(Collectors.toList());
    }
}