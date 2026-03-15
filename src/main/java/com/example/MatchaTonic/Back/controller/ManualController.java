package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ManualResponseDto;
import com.example.MatchaTonic.Back.entity.manual.Manual;
import com.example.MatchaTonic.Back.repository.ManualRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/manuals")
@RequiredArgsConstructor
public class ManualController {

    private final ManualRepository manualRepository;

    // 전체 조회 / 조건 조회
    @GetMapping
    public List<ManualResponseDto> getManuals(
            @RequestParam(name = "target", defaultValue = "PLAN") String target,
            @RequestParam(name = "category", required = false) String category
    ) {
        List<Manual> results;

        if (category == null || category.isBlank() || category.equalsIgnoreCase("ALL")) {
            results = manualRepository.findByTargetOrderByStepOrderAsc(target);
        } else {
            results = manualRepository.findByTargetAndCategoryContainingOrderByStepOrderAsc(target, category);
        }

        return results.stream()
                .map(ManualResponseDto::new)
                .collect(Collectors.toList());
    }

    // 등록
    @PostMapping
    public ManualResponseDto saveManual(@RequestBody ManualResponseDto dto) {
        System.out.println("DEBUG: 등록 시도 title = " + dto.getTitle());
        System.out.println("DEBUG: 등록 시도 target = " + dto.getTarget());
        System.out.println("DEBUG: 등록 시도 category = " + dto.getCategory());
        System.out.println("DEBUG: 등록 시도 stepOrder = " + dto.getStepOrder());

        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("title 값이 비어 있습니다.");
        }

        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new IllegalArgumentException("content 값이 비어 있습니다.");
        }

        if (dto.getTarget() == null || dto.getTarget().isBlank()) {
            throw new IllegalArgumentException("target 값이 비어 있습니다.");
        }

        if (dto.getCategory() == null || dto.getCategory().isBlank()) {
            throw new IllegalArgumentException("category 값이 비어 있습니다.");
        }

        Manual manual = Manual.builder()
                .title(dto.getTitle())
                .content(dto.getContent())
                .target(dto.getTarget())
                .category(dto.getCategory())
                .stepOrder(dto.getStepOrder())
                .build();

        Manual savedManual = manualRepository.save(manual);

        System.out.println("DEBUG: 저장 완료 id = " + savedManual.getId());

        return new ManualResponseDto(savedManual);
    }
}