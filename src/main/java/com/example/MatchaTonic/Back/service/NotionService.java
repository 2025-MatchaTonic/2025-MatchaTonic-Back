package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.entity.project.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionService {

    private final RestTemplate restTemplate;

    // fast api 배포시 환경변수로 관리할 예정
    @Value("${notion.api.token:YOUR_DEFAULT_TOKEN}")
    private String notionToken;

    @Value("${notion.api.root-page-id:YOUR_DEFAULT_ROOT_ID}")
    private String rootParentPageId;

    public void createProjectPagesOnNotion(Project project, AiResponseDto aiResponse) {
        if (aiResponse == null || aiResponse.templates() == null) {
            log.error("AI 응답 데이터가 비어있어 노션 생성을 중단합니다.");
            return;
        }

        Map<String, String> pageKeyToIdMap = new HashMap<>();

        for (AiResponseDto.TemplateDto template : aiResponse.templates()) {
            try {
                // 부모 ID 결정 (root 혹은 이전 단계에서 생성된 페이지 ID)
                String parentId = (template.parentKey() == null)
                        ? rootParentPageId
                        : pageKeyToIdMap.get(template.parentKey());

                if (parentId == null) {
                    log.warn("부모 페이지 ID를 찾을 수 없어 Root로 대체합니다. Key: {}", template.key());
                    parentId = rootParentPageId;
                }

                String createdPageId = callNotionApi(notionToken, parentId, template);
                if (createdPageId != null) {
                    pageKeyToIdMap.put(template.key(), createdPageId);
                }
            } catch (Exception e) {
                log.error("템플릿 생성 중 개별 오류 발생 (건너뜀) - Key: {}, Error: {}", template.key(), e.getMessage());
            }
        }
    }

    private String callNotionApi(String token, String parentId, AiResponseDto.TemplateDto template) {
        String url = "https://api.notion.com/v1/pages";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Notion-Version", "2022-06-28");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("page_id", parentId));
        body.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of("text", Map.of("content", template.title()))))
        ));

        List<Map<String, Object>> children = new ArrayList<>();
        parseContentToBlocks(template.content(), children);

        // 노션 API 제약: children 블록이 너무 많으면 에러가 날 수 있으므로 체크
        if (!children.isEmpty()) {
            body.put("children", children);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return (String) response.getBody().get("id");
            }
            return null;
        } catch (Exception e) {
            log.error("노션 API 호출 실패: {}", e.getMessage());
            return null; // 한 페이지가 실패해도 전체가 터지지 않게 null 반환
        }
    }

    @SuppressWarnings("unchecked")
    private void parseContentToBlocks(Object content, List<Map<String, Object>> blocks) {
        if (content == null) return;

        try {
            if (content instanceof String text) {
                if (!text.trim().isEmpty()) blocks.add(createParagraphBlock(text));
            } else if (content instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    String title = key.toString().replace("_", " ").toUpperCase();
                    blocks.add(createHeadingBlock(title));
                    parseContentToBlocks(value, blocks);
                });
            } else if (content instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        blocks.add(createBulletBlock(s));
                    } else {
                        parseContentToBlocks(item, blocks);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("데이터 파싱 중 예상치 못한 형식 발견 (무시): {}", e.getMessage());
        }
    }

    // --- 기본 구조 유지 (Helper Methods) ---
    private Map<String, Object> createHeadingBlock(String text) {
        return Map.of("object", "block", "type", "heading_3",
                "heading_3", Map.of("rich_text", List.of(Map.of("text", Map.of("content", "📌 " + text)))));
    }

    private Map<String, Object> createParagraphBlock(String text) {
        return Map.of("object", "block", "type", "paragraph",
                "paragraph", Map.of("rich_text", List.of(Map.of("text", Map.of("content", text)))));
    }

    private Map<String, Object> createBulletBlock(String text) {
        return Map.of("object", "block", "type", "bulleted_list_item",
                "bulleted_list_item", Map.of("rich_text", List.of(Map.of("text", Map.of("content", text)))));
    }
}