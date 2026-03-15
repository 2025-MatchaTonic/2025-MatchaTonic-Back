package com.example.MatchaTonic.Back.dto;

import com.example.MatchaTonic.Back.entity.manual.Manual;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ManualResponseDto {
    private Long id;
    private String title;
    private String content;
    private String target;
    private String category;

    // Entity -> DTO 변환용 생성자
    public ManualResponseDto(Manual manual) {
        this.id = manual.getId();
        this.title = manual.getTitle();
        this.content = manual.getContent();
        this.target = manual.getTarget();
        this.category = manual.getCategory();
    }
}