package com.example.MatchaTonic.Back.dto;

import com.example.MatchaTonic.Back.entity.manual.Manual;
import lombok.Getter;

@Getter
public class ManualResponseDto {
    private final Long id;
    private final String title;
    private final String content;
    private final String target;
    private final String category;

    public ManualResponseDto(Manual manual) {
        this.id = manual.getId();
        this.title = manual.getTitle();
        this.content = manual.getContent();
        this.target = manual.getTarget();
        this.category = manual.getCategory();
    }
}