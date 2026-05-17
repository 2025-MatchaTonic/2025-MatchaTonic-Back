package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ExportRequestDto;
import com.example.MatchaTonic.Back.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    // 사용자가 선택한 답변을 바탕으로 AI 분석만 수행
    @PostMapping("/project/templates/analyze")
    public ResponseEntity<String> analyzeProjectTemplates(@RequestBody ExportRequestDto request) {
        try {
            aiService.processAnalysisOnly(request);
            return ResponseEntity.ok("AI 분석이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("AI 분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // 기생성된 분석 결과를 노션 템플릿으로 내보내기
    @PostMapping("/project/templates/export-to-notion")
    public ResponseEntity<String> exportProjectTemplatesToNotion(@RequestBody ExportRequestDto request) {

        // 노션 URL 누락 방지 방어막
        if (request.pageUrl() == null || request.pageUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("노션 페이지 URL을 입력해주세요.");
        }

        try {
            aiService.exportOnly(request);
            return ResponseEntity.ok("노션 내보내기가 성공적으로 완료되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("노션 내보내기 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}