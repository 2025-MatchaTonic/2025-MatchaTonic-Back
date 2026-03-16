package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    long countByProjectId(Long projectId);

    @Query("SELECT m FROM ChatMessage m " +
            "JOIN FETCH m.project p " +
            "LEFT JOIN FETCH m.sender u " +
            "WHERE p.id = :projectId " +
            "ORDER BY m.timestamp ASC")
    List<ChatMessage> findByProjectIdWithDetails(@Param("projectId") Long projectId);
}