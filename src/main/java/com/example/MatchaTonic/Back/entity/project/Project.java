package com.example.MatchaTonic.Back.entity.project;

import com.example.MatchaTonic.Back.entity.login.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(nullable = false, unique = true)
    private String inviteCode;

    @Column(nullable = false)
    private String status;

    // AI 상태 유지 필드 추가
    @Column(name = "ai_current_status")
    private String aiCurrentStatus = "EXPLORE";

    @Column(name = "ai_collected_data", columnDefinition = "TEXT")
    private String aiCollectedData = "{}";

    @OneToOne(mappedBy = "project", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private ProjectSessionSummary projectSessionSummary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leader_id")
    private User leader;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProjectMember> members = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatMessage> chatMessages = new ArrayList<>();

    @Builder
    public Project(String name, String subject, User leader) {
        this.name = (name != null && !name.isEmpty()) ? name : "새 프로젝트";
        this.subject = (subject != null) ? subject : "주제를 입력해주세요.";
        this.leader = leader;
        this.inviteCode = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.status = "IDEA";
    }

    public void updateName(String name) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
    }

    public void updateSubject(String subject) {
        this.subject = subject;
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    // AI 컨텍스트 업데이트 메서드
    public void updateAiContext(String status, String collectedDataJson) {
        if (status != null && !status.isEmpty()) {
            this.aiCurrentStatus = status;
        }
        if (collectedDataJson != null) {
            this.aiCollectedData = collectedDataJson;
        }
    }

    // JSON 문자열을 Map으로 변환
    public Map<String, Object> getAiCollectedDataMap() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(this.aiCollectedData, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public String getSessionSummaryText() {
        if (projectSessionSummary == null || projectSessionSummary.getGoal() == null) {
            return "아직 생성된 요약이 없습니다.";
        }
        return projectSessionSummary.getGoal();
    }
}