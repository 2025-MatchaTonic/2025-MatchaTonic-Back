package com.example.MatchaTonic.Back.entity.project;

import com.example.MatchaTonic.Back.entity.login.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "project_session_summaries")
public class ProjectSessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", unique = true)
    private Project project;

    private String title;         // 프로젝트 명칭

    @Column(columnDefinition = "TEXT")
    private String goal;          // 목표

    private String teamSize;      // 팀 규모

    @Column(columnDefinition = "TEXT")
    private String roles;         // 역할 분담

    private String dueDate;

    @Column(columnDefinition = "TEXT")
    private String deliverables;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;

    private String updatedSource;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    public ProjectSessionSummary(Project project, String title, String goal, String teamSize,
                                 String roles, String dueDate, String deliverables,
                                 User updatedBy, String updatedSource) {
        this.project = project;
        this.title = title;
        this.goal = goal;
        this.teamSize = teamSize;
        this.roles = roles;
        this.dueDate = dueDate;
        this.deliverables = deliverables;
        this.updatedBy = updatedBy;
        this.updatedSource = updatedSource;
    }

    // 데이터를 Map으로 변환하여 AI 요청 시 Merge하기 편하게 해주는 메서드
    public Map<String, Object> toDataMap() {
        Map<String, Object> data = new HashMap<>();
        if (title != null) data.put("title", title);
        if (goal != null) data.put("goal", goal);
        if (teamSize != null) data.put("teamSize", teamSize);
        if (roles != null) data.put("roles", roles);
        if (dueDate != null) data.put("dueDate", dueDate);
        if (deliverables != null) data.put("deliverables", deliverables);
        return data;
    }

    // 업데이트를 위한 편의 메서드
    public void updateAll(String title, String goal, String teamSize, String roles,
                          String dueDate, String deliverables, User updatedBy, String updatedSource) {
        this.title = title;
        this.goal = goal;
        this.teamSize = teamSize;
        this.roles = roles;
        this.dueDate = dueDate;
        this.deliverables = deliverables;
        this.updatedBy = updatedBy;
        this.updatedSource = updatedSource;
    }
}