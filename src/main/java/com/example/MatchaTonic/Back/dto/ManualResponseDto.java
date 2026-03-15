package com.example.MatchaTonic.Back.dto;

import com.example.MatchaTonic.Back.entity.manual.Manual;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ManualResponseDto {

    private Long id;
    private String title;
    private String content;
    private String target;
    private String category;
    private int stepOrder;

    // Entity -> DTO
    public ManualResponseDto(Manual manual) {
        if (manual != null) {
            this.id = manual.getId();
            this.title = manual.getTitle();
            this.content = manual.getContent();
            this.target = manual.getTarget();
            this.category = manual.getCategory();
            this.stepOrder = manual.getStepOrder();
        }
    }
}