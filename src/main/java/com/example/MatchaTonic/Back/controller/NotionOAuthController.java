package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.NotionOAuthDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.service.NotionOAuthService;
import com.example.MatchaTonic.Back.service.NotionService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/notion")
@RequiredArgsConstructor
public class NotionOAuthController {

    private final UserRepository userRepository;
    private final NotionOAuthService notionOAuthService;
    private final NotionService notionService;

    @GetMapping("/oauth/start")
    public ResponseEntity<NotionOAuthDto.StartResponse> startOAuth(
            @Parameter(hidden = true) @AuthenticationPrincipal String email,
            @RequestParam(required = false) String returnTo
    ) {
        User user = getUserFromEmail(email);
        return ResponseEntity.ok(new NotionOAuthDto.StartResponse(notionOAuthService.createAuthorizationUrl(user, returnTo)));
    }

    @GetMapping("/oauth/callback")
    public void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(notionOAuthService.handleCallback(code, state, error));
    }

    @GetMapping("/connection")
    public ResponseEntity<NotionOAuthDto.StatusResponse> connectionStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal String email
    ) {
        User user = getUserFromEmail(email);
        return ResponseEntity.ok(notionOAuthService.getStatus(user));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(
            @Parameter(hidden = true) @AuthenticationPrincipal String email
    ) {
        User user = getUserFromEmail(email);
        user.disconnectNotion();
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pages")
    public ResponseEntity<NotionOAuthDto.PagesResponse> listPages(
            @Parameter(hidden = true) @AuthenticationPrincipal String email
    ) {
        User user = getUserFromEmail(email);
        if (!user.hasNotionConnection()) {
            throw new IllegalStateException("Notion connection is required.");
        }
        return ResponseEntity.ok(new NotionOAuthDto.PagesResponse(
                notionService.listAccessiblePages(user.getNotionAccessToken())
        ));
    }

    private User getUserFromEmail(String email) {
        if (email == null || email.isBlank() || "anonymousUser".equals(email)) {
            throw new IllegalStateException("Authentication is required.");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User was not found."));
    }
}
