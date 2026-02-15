package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // 특정 프로젝트의 메시지 개수를 세어 첫 입장인지 확인 (CHAT-04용)
    long countByProjectId(Long projectId);

    // 이전 대화 내역 불러오기용
    List<ChatMessage> findByProjectIdOrderByTimestampAsc(Long projectId);
}