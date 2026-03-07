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

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ProjectRepository projectRepository;
    private final NotionService notionService;
    private final RestTemplate restTemplate;

    // 환경변수에서 가져오도록 변경 (기본값 설정)
    @Value("${external.api.fastapi.url:http://your-fastapi-url:8000/ai/generate}")
    private String fastApiUrl;

    public void processAndExport(ExportRequestDto request) {
        // 1. DB에서 프로젝트 먼저 조회 (기초 데이터가 없으면 AI 호출도 불필요)
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new RuntimeException("해당 프로젝트를 찾을 수 없습니다. ID: " + request.projectId()));

        AiResponseDto aiResponse = null;

        try {
            log.info("FastAPI 분석 요청 시작 - ProjectID: {}", request.projectId());

            // 2. FastAPI 호출
            aiResponse = restTemplate.postForObject(fastApiUrl, request, AiResponseDto.class);

            // 응답 결과 검증
            if (aiResponse == null || aiResponse.templates() == null || aiResponse.templates().isEmpty()) {
                log.error("AI 서버로부터 빈 응답을 받았습니다.");
                throw new RuntimeException("AI 분석 결과가 비어있습니다.");
            }

        } catch (ResourceAccessException e) {
            log.error("AI 서버 연결 실패 (Timeout 또는 서버 다운): {}", e.getMessage());
            throw new RuntimeException("AI 분석 서버와 통신할 수 없습니다. 서버 상태를 확인해주세요.");
        } catch (Exception e) {
            log.error("AI 분석 중 예상치 못한 오류 발생: {}", e.getMessage());
            throw new RuntimeException("AI 분석 처리 중 오류가 발생했습니다: " + e.getMessage());
        }

        // 3. 수합된 데이터를 NotionService로 전달
        try {
            log.info("노션 내보내기 시작 - Project: {}", project.getName());
            notionService.createProjectPagesOnNotion(project, aiResponse);
        } catch (Exception e) {
            log.error("노션 내보내기 단계에서 오류 발생: {}", e.getMessage());
            throw new RuntimeException("노션 연동 중 오류가 발생했습니다.");
        }
    }
}