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
    private final NotionService notionService;
    private final RestTemplate restTemplate;

    @Value("${external.api.fastapi.url}")
    private String fastApiUrl;

    public void processAndExport(ExportRequestDto request) {
        // 1. 기초 데이터 조회
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new RuntimeException("해당 프로젝트를 찾을 수 없습니다. ID: " + request.projectId()));

        // 2. AI 서버 전송용 Payload 조립
        Map<String, Object> aiRequestPayload = createAiRequestPayload(project, request);

        AiResponseDto aiResponse;

        try {
            log.info("FastAPI 분석 요청 시작 - ProjectID: {}, Endpoint: {}", request.projectId(), fastApiUrl);

            // 3. FastAPI 호출
            aiResponse = restTemplate.postForObject(fastApiUrl, aiRequestPayload, AiResponseDto.class);

            if (aiResponse == null || aiResponse.templates() == null || aiResponse.templates().isEmpty()) {
                log.error("AI 서버로부터 빈 응답을 받았습니다.");
                throw new RuntimeException("AI 분석 결과가 비어있습니다.");
            }

        } catch (ResourceAccessException e) {
            log.error("AI 서버 연결 실패: {}", e.getMessage());
            throw new RuntimeException("AI 분석 서버와 통신할 수 없습니다.");
        } catch (Exception e) {
            log.error("AI 분석 중 오류 발생: {}", e.getMessage());
            throw new RuntimeException("AI 분석 처리 중 오류가 발생했습니다.");
        }

        // 4. NotionService 호출
        try {
            log.info("노션 내보내기 시작 - Project: {}, Target URL: {}", project.getName(), request.pageUrl());
            notionService.createProjectPagesOnNotion(aiResponse, request.notionToken(), request.pageUrl());
        } catch (Exception e) {
            log.error("노션 내보내기 실패: {}", e.getMessage());
            throw new RuntimeException("노션 연동 중 오류 발생: " + e.getMessage());
        }
    }

    private Map<String, Object> createAiRequestPayload(Project project, ExportRequestDto request) {
        Map<String, Object> payload = new HashMap<>();

        payload.put("projectId", project.getId());
        payload.put("templateType", request.templateType() != null ? request.templateType() : "plan");
        payload.put("currentStatus", project.getStatus() != null ? project.getStatus() : "READY");
        payload.put("content", request.content() != null ? request.content() : "템플릿 생성 요청");

        Map<String, String> collectedData = new HashMap<>();
        collectedData.put("title", project.getName());
        collectedData.put("goal", project.getSubject() != null ? project.getSubject() : "");
        collectedData.put("teamSize", "미지정");
        collectedData.put("roles", "미지정");
        collectedData.put("deliverables", "기획/개발 산출물");
        collectedData.put("dueDate", project.getCreatedAt() != null ? project.getCreatedAt().toString() : "");

        payload.put("collectedData", collectedData);
        payload.put("recentMessages", request.selectedAnswers());
        payload.put("selectedAnswers", request.selectedAnswers());

        if (request.selectedAnswers() != null && !request.selectedAnswers().isEmpty()) {
            payload.put("selectedMessage", request.selectedAnswers().get(request.selectedAnswers().size() - 1));
        }

        return payload;
    }
}