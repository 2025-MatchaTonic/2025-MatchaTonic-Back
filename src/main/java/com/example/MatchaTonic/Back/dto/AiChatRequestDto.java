package com.example.MatchaTonic.Back.dto;

import java.util.List;
import java.util.Map;

public record AiChatRequestDto(
        Long projectId,
        String content,
        String actionType,
        String currentStatus,
        Map<String, String> collectedData,
        List<String> recentMessages,
        String selectedMessage,
        List<String> selectedAnswers
) {}