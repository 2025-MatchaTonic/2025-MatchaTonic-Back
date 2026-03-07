package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ExportRequestDto;
import com.example.MatchaTonic.Back.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    // 사용자가 선택한 답변을 바탕으로 AI 분석 후 노션 템플릿 생성 및 내보내기
    @PostMapping("/project/templates")
    public ResponseEntity<String> exportProjectTemplates(@RequestBody ExportRequestDto request) {
        aiService.processAndExport(request);
        return ResponseEntity.ok("AI 분석 및 노션 내보내기가 완료되었습니다.");
    }
}