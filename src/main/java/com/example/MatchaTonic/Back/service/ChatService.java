package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiChatRequestDto;
import com.example.MatchaTonic.Back.dto.AiChatResponseDto;
import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.ChatMessage;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectSessionSummary;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.repository.project.ChatMessageRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectSessionSummaryRepository;
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

    // 정형 데이터 리포지토리
    private final ProjectSessionSummaryRepository summaryRepository;

    @Value("${external.api.fastapi.chat-url}")
    private String aiChatUrl;

    /**
     * 메시지 저장 및 브로드캐스팅
     */
    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        if (dto.getType() == null) {
            dto.setType(ChatMessageDto.MessageType.TALK);
        }

        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        if (dto.getCreatedAt() == null) {
            dto.setCreatedAt(LocalDateTime.now());
        }

        log.info("Broadcasting message to /sub/project/{}", dto.getProjectId());
        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        boolean isTalk = ChatMessageDto.MessageType.TALK.equals(dto.getType());
        boolean isNotAi = !"ai@promate.ai".equals(dto.getSenderEmail());
        boolean hasAiMention = dto.getMessage() != null && dto.getMessage().contains("@mates");

        if (isTalk && isNotAi && hasAiMention) {
            log.info("AI 호출 조건을 만족함 (@mates 감지): 프로젝트ID={}", project.getId());
            callAiAndBroadcast(project, dto);
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
                String aiContent = response.content();

                ChatMessageDto aiDto = ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.SYSTEM)
                        .projectId(project.getId())
                        .senderName("Promate AI")
                        .senderEmail("ai@promate.ai")
                        .message(aiContent)
                        .createdAt(LocalDateTime.now())
                        .build();

                saveToDb(aiDto, project);

                if (shouldUpdateSummary(aiContent)) {
                    updateProjectSummary(project, aiContent);
                }

                messagingTemplate.convertAndSend("/sub/project/" + project.getId(), aiDto);
            }
        } catch (Exception e) {
            log.error("AI 응답 처리 중 오류 발생: {}", e.getMessage());
        }
    }

    private boolean shouldUpdateSummary(String content) {
        if (content == null) return false;
        boolean hasKeyWords = content.contains("요약") || content.contains("결정") ||
                content.contains("확정") || content.contains("정리") ||
                content.contains("선정");
        boolean isLongEnough = content.length() > 100;
        return hasKeyWords || isLongEnough;
    }

    /**
     * AI 응답을 정형 데이터 테이블에 저장하는 로직
     */
    @Transactional
    protected void updateProjectSummary(Project project, String aiContent) {
        try {
            ProjectSessionSummary summary = summaryRepository.findByProject(project)
                    .orElseGet(() -> ProjectSessionSummary.builder()
                            .project(project)
                            .updatedSource("AI")
                            .build());

            summary.updateAll(
                    project.getName(),   // title
                    aiContent,           // goal
                    "논의 중",            // teamSize
                    "분석 중",            // roles
                    "미정",               // dueDate
                    "기획안",             // deliverables
                    null,                // updatedBy
                    "AI"
            );

            summaryRepository.save(summary);

            // 2. 상태 변경 (기획 완료 단계로 진입)
            if (aiContent.length() > 20) {
                project.updateStatus("PLANNING_DONE");
            }

            projectRepository.save(project);
            log.info("정형 세션 요약 테이블 및 프로젝트 상태(PLANNING_DONE) 자동 갱신 완료: ID={}", project.getId());
        } catch (Exception e) {
            log.error("세션 요약 데이터 정형화 저장 실패: {}", e.getMessage());
        }
    }

    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;
        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank()) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

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