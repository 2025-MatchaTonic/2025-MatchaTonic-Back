package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.ChatMessageDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.ChatMessage;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.repository.project.ChatMessageRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final SimpMessageSendingOperations messagingTemplate;

    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        User sender = null;
        if (dto.getSenderEmail() != null) {
            sender = userRepository.findByEmail(dto.getSenderEmail()).orElse(null);
        }

        // 1. DTO를 엔티티로 변환하여 DB 저장 (CHAT-02)
        ChatMessage chatMessage = ChatMessage.builder()
                .project(project)
                .sender(sender)
                .message(dto.getMessage())
                .type(dto.getType() == ChatMessageDto.MessageType.SYSTEM ?
                        ChatMessage.MessageType.SYSTEM : ChatMessage.MessageType.TALK)
                .build();
        chatMessageRepository.save(chatMessage);

        // 2. 실시간 브로드캐스팅
        messagingTemplate.convertAndSend("/sub/chat/room/" + dto.getProjectId(), dto);
    }

    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow();

        // [CHAT-04] 주제가 없고 첫 입장인 경우 AI가 시스템 메시지(CHAT-03) 발송
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
}