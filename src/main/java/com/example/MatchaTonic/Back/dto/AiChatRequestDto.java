package com.example.MatchaTonic.Back.dto;

import lombok.Builder;
import java.util.List;
import java.util.Map;

// AI 서버(FastAPI)로 전달할 요청 DTO
@Builder
public record AiChatRequestDto(
        Long projectId,
        String content,
        String actionType,
        String currentStatus,
        Map<String, String> collectedData,
        List<String> recentMessages,
        String selectedMessage,
        List<String> selectedAnswers
) {
}