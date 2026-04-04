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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

            // 1. 기존 JSON 데이터 가져오기
            Map<String, Object> collectedData = project.getAiCollectedDataMap();

            // 2.  수동 수정된 최신 세션 요약 데이터가 있다면 JSON 데이터에 Merge
            summaryRepository.findByProject(project).ifPresent(summary -> {
                collectedData.putAll(summary.toDataMap());
            });

            if (!collectedData.containsKey("title")) {
                collectedData.put("title", project.getName());
            }

            AiChatRequestDto request = AiChatRequestDto.builder()
                    .projectId(project.getId())
                    .content(userDto.getMessage())
                    .actionType("CHAT")
                    .currentStatus(currentStatus)
                    .collectedData(collectedData)
                    .recentMessages(recentMessages)
                    .selectedMessage(userDto.getMessage())
                    .selectedAnswers(recentMessages)
                    .build();

            log.info("AI 호출 (상태: {}): {}", currentStatus, aiChatUrl);
            AiChatResponseDto response = restTemplate.postForObject(aiChatUrl, request, AiChatResponseDto.class);

            if (response != null) {
                // 3. [수정 포인트] 응답받은 최신 데이터를 Project JSON 컬럼과 요약 테이블 양쪽 모두에 반영
                updateProjectAndSummary(project, response);

                if (response.content() != null) {
                    ChatMessageDto aiDto = ChatMessageDto.builder()
                            .type(ChatMessageDto.MessageType.SYSTEM)
                            .projectId(project.getId())
                            .senderName("Promate AI")
                            .senderEmail("ai@promate.ai")
                            .message(response.content())
                            .timestamp(LocalDateTime.now())
                            .build();

                    saveToDb(aiDto, project);
                    messagingTemplate.convertAndSend("/sub/project/" + project.getId(), aiDto);
                }
            }
        } catch (Exception e) {
            log.error("AI 응답 처리 오류: {}", e.getMessage());
        }
    }


    @Transactional
    protected void updateProjectAndSummary(Project project, AiChatResponseDto response) {
        try {
            // 1. Project 엔티티의 AI 컨텍스트(JSON) 업데이트
            String newStatus = response.currentStatus();
            String newCollectedDataJson = objectMapper.writeValueAsString(response.collectedData());
            project.updateAiContext(newStatus, newCollectedDataJson);
            projectRepository.save(project);

            // 2. ProjectSessionSummary 테이블 필드 단위 동기화
            Map<String, Object> data = response.collectedData();
            ProjectSessionSummary summary = summaryRepository.findByProject(project)
                    .orElseGet(() -> ProjectSessionSummary.builder().project(project).build());

            summary.updateAll(
                    (String) data.getOrDefault("title", summary.getTitle()),
                    (String) data.getOrDefault("goal", summary.getGoal()),
                    (String) data.getOrDefault("teamSize", summary.getTeamSize()),
                    (String) data.getOrDefault("roles", summary.getRoles()),
                    (String) data.getOrDefault("dueDate", summary.getDueDate()),
                    (String) data.getOrDefault("deliverables", summary.getDeliverables()),
                    null, "AI"
            );
            summaryRepository.save(summary);
        } catch (Exception e) {
            log.error("데이터 동기화 실패: {}", e.getMessage());
        }
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