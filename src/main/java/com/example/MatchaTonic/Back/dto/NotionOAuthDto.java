package com.example.MatchaTonic.Back.dto;

import java.util.List;

public final class NotionOAuthDto {

    private NotionOAuthDto() {
    }

    public record StartResponse(String authorizationUrl) {
    }

    public record StatusResponse(
            boolean connected,
            String workspaceId,
            String workspaceName,
            String connectedAt
    ) {
    }

    public record PageResponse(
            String id,
            String title,
            String url
    ) {
    }

    public record PagesResponse(List<PageResponse> pages) {
    }
}
