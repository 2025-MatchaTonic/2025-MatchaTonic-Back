package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.dto.ExportRequestDto;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final NotionService notionService;
    private final RestTemplate restTemplate;

    @Value("${external.api.fastapi.url}")
    private String fastApiUrl;

    public void processAndExport(ExportRequestDto request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다. ID: " + request.projectId()));

        Map<String, Object> aiRequestPayload = createAiRequestPayload(project, request);
        AiResponseDto aiResponse;

        try {
            log.info("FastAPI 분석 요청 - ProjectID: {}", request.projectId());
            log.info("Export currentStatus={}", aiRequestPayload.get("currentStatus"));
            log.info("Export collectedData={}", aiRequestPayload.get("collectedData"));

            aiResponse = restTemplate.postForObject(fastApiUrl, aiRequestPayload, AiResponseDto.class);

            if (aiResponse != null && aiResponse.templates() != null) {
                // export 단계에서는 AI 응답으로 수동 summary를 덮어쓰지 않음
                notionService.createProjectPagesOnNotion(
                        aiResponse,
                        request.notionToken(),
                        request.pageUrl()
                );
            }
        } catch (Exception e) {
            log.error("AI 분석 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("AI 처리 실패", e);
        }
    }

    private Map<String, Object> createAiRequestPayload(Project project, ExportRequestDto request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", project.getId());
        payload.put("templateType", hasText(request.templateType()) ? request.templateType() : "plan");

        // DB에 저장된 최신 상태 사용
        payload.put("currentStatus", hasText(project.getAiCurrentStatus()) ? project.getAiCurrentStatus() : "EXPLORE");
        payload.put("content", hasText(request.content()) ? request.content() : "템플릿 생성 요청");

        // 핵심: ai_collected_data + summary 확정값을 공통 규칙으로 merge
        Map<String, Object> collectedData = projectService.buildCollectedData(project);

        payload.put("collectedData", collectedData);
        payload.put("recentMessages", request.selectedAnswers());
        payload.put("selectedAnswers", request.selectedAnswers());

        if (request.selectedAnswers() != null && !request.selectedAnswers().isEmpty()) {
            payload.put("selectedMessage", request.selectedAnswers().get(request.selectedAnswers().size() - 1));
        } else {
            payload.put("selectedMessage", hasText(request.content()) ? request.content() : "템플릿 생성 요청");
        }

        return payload;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}