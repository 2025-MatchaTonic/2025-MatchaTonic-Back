package com.example.MatchaTonic.Back.entity.manual;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "manuals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Manual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private String category;

    private int stepOrder;

    @Builder
    public Manual(String title, String content, String target, String category, int stepOrder) {
        this.title = title;
        this.content = content;
        this.target = target;
        this.category = category;
        this.stepOrder = stepOrder;
    }
}