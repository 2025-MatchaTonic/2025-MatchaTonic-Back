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

import java.time.format.DateTimeFormatter;
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

        // 1. DB에 메시지 저장 (ENTER 제외한 모든 타입 저장)
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        // 2. 실시간 브로드캐스팅 (통일된 경로: /sub/project/)
        messagingTemplate.convertAndSend("/sub/project/" + dto.getProjectId(), dto);

        // 3. 사용자 메시지(TALK)일 경우 AI 응답 호출
        if (ChatMessageDto.MessageType.TALK.equals(dto.getType())) {
            callAiAndBroadcast(project, dto);
        }
    }

    private void callAiAndBroadcast(Project project, ChatMessageDto userDto) {
        try {
            // 최근 대화 내역 조회
            List<String> recentMessages = chatMessageRepository
                    .findByProjectIdOrderByTimestampAsc(project.getId())
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

            // AI 서버 호출
            AiChatResponseDto response = restTemplate.postForObject(aiChatUrl, request, AiChatResponseDto.class);

            if (response != null && response.content() != null) {
                ChatMessageDto aiDto = ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.SYSTEM)
                        .projectId(project.getId())
                        .senderName("Promate AI")
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
        // 이메일이 있으면 유저를 찾고, 없으면 시스템(AI) 메시지로 간주
        if (dto.getSenderEmail() != null && !dto.getSenderEmail().isBlank()) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

        ChatMessage chatMessage = ChatMessage.builder()
                .project(project)
                .sender(sender)
                .message(dto.getMessage())
                .type(dto.getType() == ChatMessageDto.MessageType.SYSTEM ?
                        ChatMessage.MessageType.SYSTEM : ChatMessage.MessageType.TALK)
                .build();

        chatMessageRepository.save(chatMessage);
        log.info("채팅 저장 성공: {}", dto.getMessage());
    }

    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 주제가 없고 메시지가 하나도 없을 때만 인사말 전송
        if ((project.getSubject() == null || project.getSubject().isEmpty())
                && chatMessageRepository.countByProjectId(projectId) == 0) {

            ChatMessageDto aiMsg = ChatMessageDto.builder()
                    .type(ChatMessageDto.MessageType.SYSTEM)
                    .projectId(projectId)
                    .senderName("Promate AI")
                    .message("반가워요! 아직 프로젝트 주제가 정해지지 않았네요. 어떤 아이디어를 가지고 계신가요?")
                    .build();

            saveAndSendMessage(aiMsg);
        }
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatMessages(Long projectId) {
        return chatMessageRepository.findByProjectIdOrderByTimestampAsc(projectId).stream()
                .map(m -> ChatMessageDto.builder()
                        .type(m.getType() == ChatMessage.MessageType.SYSTEM ?
                                ChatMessageDto.MessageType.SYSTEM : ChatMessageDto.MessageType.TALK)
                        .projectId(m.getProject().getId())
                        .senderEmail(m.getSender() != null ? m.getSender().getEmail() : null)
                        .senderName(m.getSender() != null ? m.getSender().getName() : "Promate AI")
                        .message(m.getMessage())
                        .createdAt(m.getTimestamp())
                        .build())
                .collect(Collectors.toList());
    }
}