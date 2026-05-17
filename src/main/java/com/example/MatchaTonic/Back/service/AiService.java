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

    // AI 분석만 수행하는 메서드 (노션 호출 제외)
    public void processAnalysisOnly(ExportRequestDto request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다. ID: " + request.projectId()));

        Map<String, Object> aiRequestPayload = createAiRequestPayload(project, request);

        try {
            log.info("FastAPI 분석 요청 (노션 제외) - ProjectID: {}", request.projectId());
            log.info("Analyze currentStatus={}", aiRequestPayload.get("currentStatus"));

            // FastAPI 호출하여 분석만 진행 (결과는 반환받지만 노션으로 쏘지 않음)
            restTemplate.postForObject(fastApiUrl, aiRequestPayload, AiResponseDto.class);

        } catch (Exception e) {
            log.error("AI 분석 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("AI 분석 실패", e);
        }
    }

    // AI 분석 결과 생성 및 노션으로 내보내는 메서드
    public void exportOnly(ExportRequestDto request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new RuntimeException("프로젝트를 찾을 수 없습니다. ID: " + request.projectId()));

        Map<String, Object> aiRequestPayload = createAiRequestPayload(project, request);
        AiResponseDto aiResponse;

        try {
            log.info("FastAPI 분석 및 노션 내보내기 - ProjectID: {}", request.projectId());
            aiResponse = restTemplate.postForObject(fastApiUrl, aiRequestPayload, AiResponseDto.class);

            notionService.createProjectPagesOnNotion(
                    aiResponse,
                    request.notionToken(),
                    request.pageUrl()
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("노션 내보내기 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("노션 내보내기 실패", e);
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
