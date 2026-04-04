package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.dto.ExportRequestDto;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
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
            aiResponse = restTemplate.postForObject(fastApiUrl, aiRequestPayload, AiResponseDto.class);

            if (aiResponse != null && aiResponse.templates() != null) {
                projectService.updateSummaryFromAi(request.projectId(), aiResponse);
                notionService.createProjectPagesOnNotion(aiResponse, request.notionToken(), request.pageUrl());
            }
        } catch (Exception e) {
            log.error("AI 분석 중 오류 발생: {}", e.getMessage());
            throw new RuntimeException("AI 처리 실패");
        }
    }

    private Map<String, Object> createAiRequestPayload(Project project, ExportRequestDto request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", project.getId());
        payload.put("templateType", request.templateType() != null ? request.templateType() : "plan");

        // DB에 저장된 최신 상태와 데이터 사용
        payload.put("currentStatus", project.getAiCurrentStatus());
        payload.put("content", request.content() != null ? request.content() : "템플릿 생성 요청");

        Map<String, Object> collectedData = project.getAiCollectedDataMap();

        // title이 난수 방 이름이면 비워줌
        if (!collectedData.containsKey("title") || String.valueOf(collectedData.get("title")).contains(project.getName())) {
            collectedData.put("title", "");
        }

        payload.put("collectedData", collectedData);
        payload.put("recentMessages", request.selectedAnswers());
        payload.put("selectedAnswers", request.selectedAnswers());

        if (request.selectedAnswers() != null && !request.selectedAnswers().isEmpty()) {
            payload.put("selectedMessage", request.selectedAnswers().get(request.selectedAnswers().size() - 1));
        }

        return payload;
    }
}