package com.example.MatchaTonic.Back.entity.project;

import com.example.MatchaTonic.Back.entity.login.User;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String subject;

    @Column(nullable = false, unique = true)
    private String inviteCode;

    @Column(nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leader_id")
    private User leader;// 프로젝트 리더

    @Builder
    public Project(String name, String subject, User leader) {
        this.name = name;
        this.subject = subject;
        this.leader = leader;
        this.inviteCode = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.status = "IDEA";
    }
}