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

    // 매뉴얼 조회 API
    @GetMapping
    public List<ManualResponseDto> getManuals(
            @RequestParam(name = "version", defaultValue = "PLAN") String version,
            @RequestParam(name = "category", required = false) String category) {

        List<Manual> results;

        if (category == null || category.isEmpty() || category.equals("ALL")) {
            results = manualRepository.findByTargetOrderByStepOrderAsc(version);
        } else {
            results = manualRepository.findByVersionAndCategory(version, category);
        }

        return results.stream()
                .map(ManualResponseDto::new)
                .collect(Collectors.toList());
    }

    // 메뉴얼 등록 API (데이터 입력용)

    @PostMapping
    public ManualResponseDto saveManual(@RequestBody ManualResponseDto dto) {
        Manual manual = Manual.builder()
                .title(dto.getTitle())
                .content(dto.getContent())
                .target(dto.getTarget())
                .category(dto.getCategory())
                .stepOrder(0)
                .build();

        Manual savedManual = manualRepository.save(manual);
        return new ManualResponseDto(savedManual);
    }
}