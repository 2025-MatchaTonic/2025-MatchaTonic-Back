package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.entity.project.Project;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionService {

    private final RestTemplate restTemplate;

    /**
     * 노션 페이지 생성 메인 로직
     * @param aiResponse AI가 생성한 템플릿 리스트
     * @param userToken 사용자가 직접 입력한 노션 통합 토큰
     * @param pageUrl 사용자가 직접 입력한 부모 페이지 URL
     */
    public void createProjectPagesOnNotion(AiResponseDto aiResponse, String userToken, String pageUrl) {
        if (aiResponse == null || aiResponse.templates() == null) {
            log.error("AI 응답 데이터가 비어있어 노션 생성을 중단합니다.");
            return;
        }

        // 1. URL에서 32자리 Page ID 추출
        String rootParentPageId = extractPageId(pageUrl);
        if (rootParentPageId == null) {
            log.error("유효하지 않은 노션 URL입니다. ID를 추출할 수 없습니다: {}", pageUrl);
            throw new IllegalArgumentException("유효한 노션 페이지 URL을 입력해주세요.");
        }

        log.info("추출된 Root Page ID: {}", rootParentPageId);

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

                // 사용자가 전달한 토큰을 사용하여 호출
                String createdPageId = callNotionApi(userToken, parentId, template);
                if (createdPageId != null) {
                    pageKeyToIdMap.put(template.key(), createdPageId);
                }
            } catch (Exception e) {
                log.error("템플릿 생성 중 개별 오류 발생 (건너뜀) - Key: {}, Error: {}", template.key(), e.getMessage());
            }
        }
    }

    /**
     * 노션 URL에서 32자리 Page ID를 추출하는 유틸리티 메서드
     */
    public String extractPageId(String url) {
        if (url == null || url.isEmpty()) return null;

        // 패턴 설명: 마지막 하이픈(-) 뒤 혹은 마지막 슬래시(/) 뒤의 32자리 hex 문자열을 찾음
        // 물음표(?) 뒤의 파라미터는 무시
        Pattern pattern = Pattern.compile("([a-f0-9]{32})");

        // URL에서 쿼리 스트링 제거
        String cleanUrl = url.split("\\?")[0];
        Matcher matcher = pattern.matcher(cleanUrl);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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
            log.error("노션 API 호출 실패 (토큰 확인 필요): {}", e.getMessage());
            return null;
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