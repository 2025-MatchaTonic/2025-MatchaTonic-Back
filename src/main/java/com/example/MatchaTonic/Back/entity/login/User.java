package com.example.MatchaTonic.Back.entity.login;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(length = 4096)
    private String notionAccessToken;

    @Column(length = 4096)
    private String notionRefreshToken;

    private String notionBotId;

    private String notionWorkspaceId;

    private String notionWorkspaceName;

    private LocalDateTime notionConnectedAt;

    @Builder
    public User(String email, String name, Role role) {
        this.email = email;
        this.name = name;
        this.role = role;
    }

    public User update(String name) {
        this.name = name;
        return this;
    }

    public void connectNotion(
            String accessToken,
            String refreshToken,
            String botId,
            String workspaceId,
            String workspaceName
    ) {
        this.notionAccessToken = accessToken;
        this.notionRefreshToken = refreshToken;
        this.notionBotId = botId;
        this.notionWorkspaceId = workspaceId;
        this.notionWorkspaceName = workspaceName;
        this.notionConnectedAt = LocalDateTime.now();
    }

    public void disconnectNotion() {
        this.notionAccessToken = null;
        this.notionRefreshToken = null;
        this.notionBotId = null;
        this.notionWorkspaceId = null;
        this.notionWorkspaceName = null;
        this.notionConnectedAt = null;
    }

    public boolean hasNotionConnection() {
        return notionAccessToken != null && !notionAccessToken.isBlank();
    }
}
