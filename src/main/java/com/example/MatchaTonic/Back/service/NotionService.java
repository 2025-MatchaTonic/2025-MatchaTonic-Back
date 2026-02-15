package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotionService {

    private final ProjectRepository projectRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    public String exportToNotion(Long projectId, String notionToken, String parentPageId) {
        Project project = projectRepository.findById(projectId).orElseThrow();

        String url = "https://api.notion.com/v1/pages";

        // 노션 API 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + notionToken);
        headers.set("Notion-Version", "2022-06-28");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // [EXP-02] 노션 페이지 생성 데이터
        Map<String, Object> body = Map.of(
                "parent", Map.of("page_id", parentPageId),
                "properties", Map.of(
                        "title", List.of(Map.of("text", Map.of("content", project.getName() + " 결과물")))
                ),
                "children", List.of(
                        Map.of("object", "block", "type", "heading_2", "heading_2", Map.of("rich_text", List.of(Map.of("text", Map.of("content", "1. 프로젝트 주제"))))),
                        Map.of("object", "block", "type", "paragraph", "paragraph", Map.of("rich_text", List.of(Map.of("text", Map.of("content", project.getSubject())))))
                )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            return "노션으로 성공적으로 보냈습니다!";
        } catch (Exception e) {
            return "노션 연동 실패: " + e.getMessage();
        }
    }
}