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

    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 1. DB 저장 (ENTER 타입 제외)
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        // 2. 브로드캐스팅 (senderEmail, senderName이 포함된 dto를 그대로 전송하여 프론트 중복 방지)
        log.info("Broadcasting message from {}: {}", dto.getSenderEmail(), dto.getMessage());
        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        // 3. AI 응답 처리
        if (ChatMessageDto.MessageType.TALK.equals(dto.getType())) {
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
                        .senderEmail("ai@promate.ai") // AI 식별용 가상 이메일
                        .message(response.content())
                        .build();

                saveToDb(aiDto, project);
                messagingTemplate.convertAndSend("/sub/project/" + project.getId(), aiDto);
            }
        } catch (Exception e) {
            log.error("AI 채팅 응답 호출 실패: {}", e.getMessage());
        }
    }

    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;
        // dto에 senderEmail이 있으면 DB에서 유저를 찾아 연결
        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank()) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

        ChatMessage chatMessage = ChatMessage.builder()
                .project(project)
                .sender(sender) // 찾은 유저 저장 (null이면 SYSTEM 메시지로 처리됨)
                .message(dto.getMessage())
                .type(dto.getType() == ChatMessageDto.MessageType.SYSTEM ?
                        ChatMessage.MessageType.SYSTEM : ChatMessage.MessageType.TALK)
                .build();

        chatMessageRepository.save(chatMessage);
        log.info("DB 저장 완료: [발신자: {}] [내용: {}]", dto.getSenderEmail(), dto.getMessage());
    }

    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        if ((project.getSubject() == null || project.getSubject().isEmpty())
                && chatMessageRepository.countByProjectId(projectId) == 0) {

            ChatMessageDto aiMsg = ChatMessageDto.builder()
                    .type(ChatMessageDto.MessageType.SYSTEM)
                    .projectId(projectId)
                    .senderName("Promate AI")
                    .senderEmail("ai@promate.ai")
                    .message("반가워요! 아직 프로젝트 주제가 정해지지 않았네요. 어떤 아이디어를 가지고 계신가요?")
                    .build();

            saveAndSendMessage(aiMsg);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatMessages(Long projectId) {
        return chatMessageRepository.findByProjectIdWithDetails(projectId).stream()
                .map(m -> ChatMessageDto.builder()
                        .type(m.getType() == ChatMessage.MessageType.SYSTEM ?
                                ChatMessageDto.MessageType.SYSTEM : ChatMessageDto.MessageType.TALK)
                        .projectId(m.getProject().getId())
                        .senderEmail(m.getSender() != null ? m.getSender().getEmail() : "ai@promate.ai")
                        .senderName(m.getSender() != null ? m.getSender().getName() : "Promate AI")
                        .message(m.getMessage())
                        .createdAt(m.getTimestamp())
                        .build())
                .collect(Collectors.toList());
    }
}