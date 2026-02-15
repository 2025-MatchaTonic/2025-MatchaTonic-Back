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

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final SimpMessageSendingOperations messagingTemplate;

    //  [CHAT-02] 메시지 저장 및 실시간 전송
    @Transactional
    public void saveAndSendMessage(ChatMessageDto dto) {
        Project project = projectRepository.findById(dto.getProjectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 1. ENTER 타입이 아닐 때만 DB에 저장 (실시간 알림은 저장할 필요 없음)
        if (!ChatMessageDto.MessageType.ENTER.equals(dto.getType())) {
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

        // 2. 실시간 브로드캐스팅 (ENTER 포함 모든 타입 전송)
        messagingTemplate.convertAndSend("/sub/chat/room/" + dto.getProjectId(), dto);
    }

    //  [CHAT-04] 프로젝트 진입 시 주제 존재 여부 판단 및 AI 첫 인사
    @Transactional
    public void checkSubjectAndInitiateAI(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다."));

        // 주제가 없고 첫 입장인 경우 AI가 시스템 메시지(CHAT-03) 발송
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

    //[CHAT-02] 이전 대화 내역 불러오기
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getChatMessages(Long projectId) {
        // DB에서 해당 프로젝트의 메시지만 시간순으로 조회
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