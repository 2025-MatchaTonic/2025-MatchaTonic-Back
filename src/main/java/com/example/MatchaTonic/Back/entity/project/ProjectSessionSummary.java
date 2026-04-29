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

    private String title;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String goal;

    private String teamSize;

    @Column(columnDefinition = "TEXT")
    private String roles;

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
    public ProjectSessionSummary(Project project, String title, String subject, String goal, String teamSize,
                                 String roles, String dueDate, String deliverables,
                                 User updatedBy, String updatedSource) {
        this.project = project;
        this.title = title;
        this.subject = subject;
        this.goal = goal;
        this.teamSize = teamSize;
        this.roles = roles;
        this.dueDate = dueDate;
        this.deliverables = deliverables;
        this.updatedBy = updatedBy;
        this.updatedSource = updatedSource;
    }

    public Map<String, Object> toDataMap() {
        Map<String, Object> data = new HashMap<>();
        if (title != null) data.put("title", title);
        if (subject != null) data.put("subject", subject);
        if (goal != null) data.put("goal", goal);
        if (teamSize != null) data.put("teamSize", teamSize);
        if (roles != null) data.put("roles", roles);
        if (dueDate != null) data.put("dueDate", dueDate);
        if (deliverables != null) data.put("deliverables", deliverables);
        return data;
    }

    public void updateAll(String title, String subject, String goal, String teamSize, String roles,
                          String dueDate, String deliverables, User updatedBy, String updatedSource) {
        this.title = title;
        this.subject = subject;
        this.goal = goal;
        this.teamSize = teamSize;
        this.roles = roles;
        this.dueDate = dueDate;
        this.deliverables = deliverables;
        this.updatedBy = updatedBy;
        this.updatedSource = updatedSource;
    }
}