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

    @PostMapping
    public ManualResponseDto saveManual(@RequestBody ManualResponseDto dto) {
        // 터미널에 null로 찍히는지 확인용
        System.out.println("DEBUG: 등록 시도 title = " + dto.getTitle());

        if (dto.getTitle() == null) {
            throw new IllegalArgumentException("데이터가 정상적으로 전달되지 않았습니다.");
        }

        Manual manual = Manual.builder()
                .title(dto.getTitle())
                .content(dto.getContent())
                .target(dto.getTarget())
                .category(dto.getCategory())
                .stepOrder(dto.getStepOrder())
                .build();

        Manual savedManual = manualRepository.save(manual);
        return new ManualResponseDto(savedManual);
    }
}