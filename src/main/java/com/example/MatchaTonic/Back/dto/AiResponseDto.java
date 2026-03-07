package com.example.MatchaTonic.Back.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record AiResponseDto(
        Long projectId,
        List<TemplateDto> templates
) {
    public record TemplateDto(
            String key,
            String parentKey,
            String title,
            Object content
    ) {}
}