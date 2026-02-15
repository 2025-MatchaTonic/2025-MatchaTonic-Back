package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotionService {

    private final ProjectRepository projectRepository;

    public String exportToNotion(Long projectId, String notionToken, String parentPageId) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        RestTemplate restTemplate = new RestTemplate();

        String url = "https://api.notion.com/v1/pages";

        // 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + notionToken);
        headers.set("Notion-Version", "2022-06-28");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // JSON 데이터 구성 (Map을 사용해 직접 생성)
        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("page_id", parentPageId));

        Map<String, Object> properties = new HashMap<>();
        properties.put("title", Map.of("title", List.of(Map.of("text", Map.of("content", project.getName() + " 결과물")))));
        body.put("properties", properties);

        // 본문 내용 추가 (Heading 및 Paragraph)
        Map<String, Object> heading = Map.of(
                "object", "block",
                "type", "heading_2",
                "heading_2", Map.of("rich_text", List.of(Map.of("text", Map.of("content", "🚀 프로젝트 요약"))))
        );

        Map<String, Object> paragraph = Map.of(
                "object", "block",
                "type", "paragraph",
                "paragraph", Map.of("rich_text", List.of(Map.of("text", Map.of("content", project.getSubject() != null ? project.getSubject() : "내용 없음"))))
        );

        body.put("children", List.of(heading, paragraph));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(url, entity, String.class);
            return "노션 내보내기 성공!";
        } catch (Exception e) {
            return "노션 연동 실패: " + e.getMessage();
        }
    }
}