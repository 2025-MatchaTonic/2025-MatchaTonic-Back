package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.NotionOAuthDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionOAuthService {

    private static final String NOTION_AUTHORIZE_URL = "https://api.notion.com/v1/oauth/authorize";
    private static final String NOTION_TOKEN_URL = "https://api.notion.com/v1/oauth/token";
    private static final String DEFAULT_REDIRECT_URI = "https://api.promate.ai.kr/api/notion/oauth/callback";
    private static final String DEFAULT_SUCCESS_REDIRECT_URL = "https://promate.ai.kr/?notion=connected#export-notion";
    private static final String DEFAULT_FAILURE_REDIRECT_URL = "https://promate.ai.kr/?notion=failed#export-notion";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final RestTemplate restTemplate;
    private final UserRepository userRepository;
    private final ConcurrentHashMap<String, PendingAuthorization> pendingStates = new ConcurrentHashMap<>();

    @Value("${notion.oauth.client-id:}")
    private String clientId;

    @Value("${notion.oauth.client-secret:}")
    private String clientSecret;

    @Value("${notion.oauth.redirect-uri:https://api.promate.ai.kr/api/notion/oauth/callback}")
    private String redirectUri;

    @Value("${notion.oauth.success-redirect:https://promate.ai.kr/?notion=connected#export-notion}")
    private String successRedirectUrl;

    @Value("${notion.oauth.failure-redirect:https://promate.ai.kr/?notion=failed#export-notion}")
    private String failureRedirectUrl;

    public String createAuthorizationUrl(User user, String returnTo) {
        assertConfigured();
        cleanupExpiredStates();

        String state = UUID.randomUUID().toString();
        pendingStates.put(state, new PendingAuthorization(
                user.getEmail(),
                sanitizeReturnTo(returnTo),
                Instant.now().plus(STATE_TTL)
        ));

        return UriComponentsBuilder.fromHttpUrl(NOTION_AUTHORIZE_URL)
                .queryParam("owner", "user")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", effectiveRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    public String handleCallback(String code, String state, String error) {
        if (error != null && !error.isBlank()) {
            return redirectWithMessage(effectiveFailureRedirectUrl(), "Notion connection was cancelled.");
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            return redirectWithMessage(effectiveFailureRedirectUrl(), "Missing Notion OAuth callback parameters.");
        }

        PendingAuthorization pending = pendingStates.remove(state);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            return redirectWithMessage(effectiveFailureRedirectUrl(), "Notion connection session expired. Please try again.");
        }

        try {
            NotionTokenResponse tokenResponse = exchangeCodeForToken(code);
            User user = userRepository.findByEmail(pending.email())
                    .orElseThrow(() -> new IllegalStateException("Authenticated user was not found."));

            user.connectNotion(
                    tokenResponse.accessToken(),
                    tokenResponse.refreshToken(),
                    tokenResponse.botId(),
                    tokenResponse.workspaceId(),
                    tokenResponse.workspaceName()
            );
            userRepository.save(user);
            return redirectWithStatus(resolveReturnTo(pending), "connected");
        } catch (Exception e) {
            log.error("Notion OAuth callback failed: {}", e.getMessage(), e);
            return redirectWithMessage(resolveReturnTo(pending), "Notion connection failed.");
        }
    }

    public NotionOAuthDto.StatusResponse getStatus(User user) {
        return new NotionOAuthDto.StatusResponse(
                user.hasNotionConnection(),
                user.getNotionWorkspaceId(),
                user.getNotionWorkspaceName(),
                user.getNotionConnectedAt() == null ? null : user.getNotionConnectedAt().toString()
        );
    }

    private NotionTokenResponse exchangeCodeForToken(String code) {
        String basicToken = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + basicToken);

        Map<String, String> body = Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", effectiveRedirectUri()
        );

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    NOTION_TOKEN_URL,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            Map<?, ?> responseBody = response.getBody();
            if (responseBody == null || responseBody.get("access_token") == null) {
                throw new RuntimeException("Notion token response did not include access_token.");
            }
            return new NotionTokenResponse(
                    asString(responseBody.get("access_token")),
                    asString(responseBody.get("refresh_token")),
                    asString(responseBody.get("bot_id")),
                    asString(responseBody.get("workspace_id")),
                    asString(responseBody.get("workspace_name"))
            );
        } catch (RestClientResponseException e) {
            throw new RuntimeException("Notion token exchange failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private void assertConfigured() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new RuntimeException("Notion OAuth is not configured. Set NOTION_CLIENT_ID and NOTION_CLIENT_SECRET.");
        }
    }

    private void cleanupExpiredStates() {
        Instant now = Instant.now();
        pendingStates.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String redirectWithMessage(String baseUrl, String message) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("message", message)
                .build()
                .toUriString();
    }

    private String redirectWithStatus(String baseUrl, String status) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("notion", status)
                .build()
                .toUriString();
    }

    private String resolveReturnTo(PendingAuthorization pending) {
        return hasText(pending.returnTo()) ? pending.returnTo() : effectiveSuccessRedirectUrl();
    }

    private String effectiveRedirectUri() {
        return hasText(redirectUri) ? redirectUri : DEFAULT_REDIRECT_URI;
    }

    private String effectiveSuccessRedirectUrl() {
        return hasText(successRedirectUrl) ? successRedirectUrl : DEFAULT_SUCCESS_REDIRECT_URL;
    }

    private String effectiveFailureRedirectUrl() {
        return hasText(failureRedirectUrl) ? failureRedirectUrl : DEFAULT_FAILURE_REDIRECT_URL;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sanitizeReturnTo(String returnTo) {
        if (!hasText(returnTo)) {
            return null;
        }
        try {
            URI uri = URI.create(returnTo);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
                return null;
            }
            if (
                    "localhost".equalsIgnoreCase(host) ||
                    "promate.ai.kr".equalsIgnoreCase(host) ||
                    host.endsWith(".promate.ai.kr") ||
                    host.endsWith(".vercel.app")
            ) {
                return returnTo;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record PendingAuthorization(String email, String returnTo, Instant expiresAt) {
    }

    private record NotionTokenResponse(
            String accessToken,
            String refreshToken,
            String botId,
            String workspaceId,
            String workspaceName
    ) {
    }
}
