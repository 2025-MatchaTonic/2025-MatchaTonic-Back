package com.example.MatchaTonic.Back.service;

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

    @Value("${external.api.fastapi.url}")
    private String aiChatUrl;

    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 1. DB에 메시지 저장
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
            saveToDb(dto, project);
        }

        // 2. 실시간 브로드캐스팅 (사용자 메시지 전송)
        messagingTemplate.convertAndSend("/sub/chat/room/" + dto.getProjectId(), dto);

        // 3. 사용자가 메시지를 보냈을 때만 AI 응답 호출
        if (ChatMessageDto.MessageType.TALK.equals(dto.getType())) {
            callAiAndReply(dto, project);
        }
    }

    // AI 서버(FastAPI) 호출 및 답변 전송
    private void callAiAndReply(ChatMessageDto userDto, Project project) {
        try {
            // AI 서버 전송용 데이터 조립 (AI 서버 규격에 맞춤)
            Map<String, Object> aiRequest = new HashMap<>();
            aiRequest.put("projectId", project.getId());
            aiRequest.put("content", userDto.getMessage());
            aiRequest.put("currentStatus", project.getStatus());

            // AI 서버 호출 (POST /ai/chat/)
            String chatApiUrl = aiChatUrl.replace("/generate", "/chat/");
            Map<String, Object> aiResponse = restTemplate.postForObject(chatApiUrl, aiRequest, Map.class);

            if (aiResponse != null && aiResponse.get("content") != null) {
                String aiReplyMessage = (String) aiResponse.get("content");

                // AI 응답 DTO 생성
                ChatMessageDto aiDto = ChatMessageDto.builder()
                        .type(ChatMessageDto.MessageType.TALK)
                        .projectId(project.getId())
                        .senderName("Promate AI")
                        .message(aiReplyMessage)
                        .build();

                // DB 저장 및 브로드캐스팅 (AI 메시지도 채팅방에 뿌림)
                saveToDb(aiDto, project);
                messagingTemplate.convertAndSend("/sub/chat/room/" + project.getId(), aiDto);
            }
        } catch (Exception e) {
            log.error("AI 채팅 응답 호출 실패: {}", e.getMessage());
            // 실패 시 사용자에게 오류 메시지를 보내지 않고 로그만 남김 (채팅 흐름 방해 방지)
        }
    }

    private void saveToDb(ChatMessageDto dto, Project project) {
        User sender = null;
        if (dto.getSenderEmail() != null) {
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
    }

    // [CHAT-04]
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