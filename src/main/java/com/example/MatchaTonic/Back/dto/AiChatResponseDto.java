package com.example.MatchaTonic.Back.dto;

import java.util.List;
import java.util.Map;

public record AiChatResponseDto(
        String content,
        List<String> suggestedQuestions,
        String currentStatus,
        boolean isSufficient,
        Map<String, String> collectedData
) {}