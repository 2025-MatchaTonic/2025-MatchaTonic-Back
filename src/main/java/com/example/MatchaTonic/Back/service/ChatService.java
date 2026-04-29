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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
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
    private final ProjectSessionSummaryRepository summaryRepository;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;

    @Value("${external.api.fastapi.chat-url}")
    private String aiChatUrl;

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

        if (dto.getTimestamp() == null) {
            dto.setTimestamp(LocalDateTime.now());
        }

        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        boolean isTalk = ChatMessageDto.MessageType.TALK.equals(dto.getType());
        boolean isNotAi = !"ai@promate.ai".equals(dto.getSenderEmail());
        boolean hasAiMention = dto.getMessage() != null && dto.getMessage().contains("@mates");

        if (isTalk && isNotAi && hasAiMention) {
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

            String currentStatus = project.getAiCurrentStatus();

            // 공통 빌더 호출
            Map<String, Object> finalData = projectService.buildCollectedData(project);
            log.info("[CHECK 3] Final Merged Data to be sent: {}", finalData);

            AiChatRequestDto request = AiChatRequestDto.builder()
                    .projectId(project.getId())
                    .content(userDto.getMessage())
                    .actionType("CHAT")
                    .currentStatus(currentStatus)
                    .collectedData(finalData)
                    .recentMessages(recentMessages)
                    .selectedMessage(userDto.getMessage())
                    .selectedAnswers(recentMessages)
                    .build();

            log.info("AI 호출 (상태: {}): {}", currentStatus, aiChatUrl);
            AiChatResponseDto response = restTemplate.postForObject(aiChatUrl, request, AiChatResponseDto.class);

            if (response != null) {
                //  보수적 업데이트 로직이 포함된 메서드 호출
                updateProjectAndSummary(project, response);

                if (response.content() != null) {
                    ChatMessageDto aiDto = ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.SYSTEM)
                            .projectId(project.getId())
                            .senderName("Promate AI")
                            .senderEmail("ai@promate.ai")
                            .message(response.content())
                            .collectedData(response.collectedData())
                            .timestamp(LocalDateTime.now())
                            .build();

                    saveToDb(aiDto, project);
                    messagingTemplate.convertAndSend("/sub/project/" + project.getId(), aiDto);
                }
            }
        } catch (Exception e) {
            log.error("AI 응답 처리 오류: {}", e.getMessage(), e);
        }
    }


    @Transactional
    protected void updateProjectAndSummary(Project project, AiChatResponseDto response) {
        try {
            // 1. AI 응답 원본은 항상 Project의 JSON 컬럼에 업데이트
            String newStatus = response.currentStatus();
            Map<String, Object> aiData = response.collectedData();
            String newCollectedDataJson = objectMapper.writeValueAsString(aiData);
            project.updateAiContext(newStatus, newCollectedDataJson);
            projectRepository.save(project);

            // 2. Summary 테이블 동기화
            ProjectSessionSummary summary = summaryRepository.findByProject(project)
                    .orElseGet(() -> ProjectSessionSummary.builder().project(project).build());

            // 헬퍼 메서드(getSafeValue)를 통해 수동값이 없을 때만 AI 값으로 채움
            summary.updateAll(
                    getSafeTitleValue(summary.getTitle(), aiData.get("title")),
                    getSafeValue(summary.getSubject(), aiData.get("subject")),
                    getSafeValue(summary.getGoal(), aiData.get("goal")),
                    getSafeValue(summary.getTeamSize(), aiData.get("teamSize")),
                    getSafeValue(summary.getRoles(), aiData.get("roles")),
                    getSafeValue(summary.getDueDate(), aiData.get("dueDate")),
                    getSafeValue(summary.getDeliverables(), aiData.get("deliverables")),
                    null, "AI_AUTO_FILL"
            );
            summaryRepository.save(summary);
        } catch (Exception e) {
            log.error("데이터 동기화 실패: {}", e.getMessage(), e);
        }
    }

    private String getSafeTitleValue(String current, Object newValue) {
        if (current == null || current.trim().isEmpty() || current.equals("새 프로젝트") || current.contains("새 프로젝트")) {
            return newValue != null ? newValue.toString() : null;
        }
        return current;
    }

    // 기존 값(current)이 존재하면 유지하고, 비어있을 때만 새 값(newValue)을 채움
    private String getSafeValue(String current, Object newValue) {
        if (current != null && !current.trim().isEmpty()) {
            return current;
        }
        return newValue != null ? newValue.toString() : null;
    }

    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        long messageCount = chatMessageRepository.countByProjectId(projectId);

        if (messageCount == 0 && (project.getSubject() == null || project.getSubject().isEmpty() || project.getSubject().contains("주제를 입력해주세요"))) {
            ChatMessageDto aiMsg = ChatMessageDto.builder()
                    .type(ChatMessageDto.MessageType.SYSTEM)
                    .projectId(projectId)
                    .senderName("Promate AI")
                    .senderEmail("ai@promate.ai")
                    .message("반가워요! 아직 프로젝트 주제가 정해지지 않았네요. 어떤 아이디어를 가지고 계신가요? @mates를 붙여 저를 불러주세요!")
                    .timestamp(LocalDateTime.now())
                    .build();

            saveToDb(aiMsg, project);
            messagingTemplate.convertAndSend("/sub/project/" + projectId, aiMsg);
        }
    }

    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;


        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank() && !"ai@promate.ai".equals(dto.getSenderEmail())) {
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
                .sender(sender) // AI일 경우 sender는 null로 저장됨
                .message(dto.getMessage())
                .type(entityType)
                .build();
        chatMessageRepository.save(chatMessage);
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
                        .timestamp(m.getTimestamp())
                        .build())
                .collect(Collectors.toList());
    }
}