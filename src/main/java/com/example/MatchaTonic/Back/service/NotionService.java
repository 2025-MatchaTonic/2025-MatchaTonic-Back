package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.NotionOAuthDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionService {

    private final RestTemplate restTemplate;

    public List<NotionOAuthDto.PageResponse> listAccessiblePages(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Notion connection is required.");
        }

        HttpHeaders headers = createNotionHeaders(token);
        Map<String, Object> body = Map.of(
                "filter", Map.of("property", "object", "value", "page"),
                "sort", Map.of("direction", "descending", "timestamp", "last_edited_time"),
                "page_size", 50
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.notion.com/v1/search",
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            Object results = response.getBody() == null ? null : response.getBody().get("results");
            if (!(results instanceof List<?> pages)) {
                return List.of();
            }
            List<NotionOAuthDto.PageResponse> mappedPages = new ArrayList<>();
            for (Object item : pages) {
                if (item instanceof Map<?, ?> page) {
                    String id = asString(page.get("id"));
                    String url = asString(page.get("url"));
                    String title = extractTitle(page);
                    mappedPages.add(new NotionOAuthDto.PageResponse(id, title, url));
                }
            }
            return mappedPages;
        } catch (RestClientResponseException e) {
            String responseBody = summarizeResponseBody(e.getResponseBodyAsString());
            throw new RuntimeException("Notion page list failed: " + e.getStatusCode() + " " + responseBody, e);
        }
    }

    /**
     * 노션 페이지 생성 메인 로직
     * @param aiResponse AI가 생성한 템플릿 리스트
     * @param userToken 사용자가 직접 입력한 노션 통합 토큰
     * @param pageUrl 사용자가 직접 입력한 부모 페이지 URL
     */
    public void createProjectPagesOnNotion(AiResponseDto aiResponse, String userToken, String pageUrl, String projectSubject, String projectSummary, List<MemberDto.InfoResponse> members) {
        if (aiResponse == null || aiResponse.templates() == null) {
            log.error("AI 응답 데이터가 비어있어 노션 생성을 중단합니다.");
            throw new IllegalArgumentException("AI가 생성한 노션 템플릿 데이터가 없습니다.");
        }

        if (userToken == null || userToken.isBlank()) {
            log.error("노션 통합 토큰이 비어있어 노션 생성을 중단합니다.");
            throw new IllegalArgumentException("노션 통합 토큰을 입력해주세요.");
        }

        // 1. URL에서 32자리 Page ID 추출
        String rootParentPageId = extractPageId(pageUrl);
        if (rootParentPageId == null) {
            log.error("유효하지 않은 노션 URL입니다. ID를 추출할 수 없습니다: {}", pageUrl);
            throw new IllegalArgumentException("유효한 노션 페이지 URL을 입력해주세요.");
        }

        log.info("추출된 Root Page ID: {}", rootParentPageId);

        Map<String, String> pageKeyToIdMap = new HashMap<>();
        List<String> failedKeys = new ArrayList<>();
        List<String> failedDetails = new ArrayList<>();
        int createdCount = 0;
        String rootCreatedPageId = null; // 루트 페이지 ID 저장

        for (AiResponseDto.TemplateDto template : aiResponse.templates()) {
            try {
                String parentId = (template.parentKey() == null)
                        ? rootParentPageId
                        : pageKeyToIdMap.get(template.parentKey());

                if (parentId == null) {
                    log.warn("부모 페이지 ID를 찾을 수 없어 Root로 대체합니다. Key: {}", template.key());
                    parentId = rootParentPageId;
                }

                String createdPageId = callNotionApi(userToken, parentId, template, projectSubject, projectSummary, members);
                if (createdPageId != null) {
                    pageKeyToIdMap.put(template.key(), createdPageId);
                    if (template.parentKey() == null) {
                        rootCreatedPageId = createdPageId;
                        createDashboardDatabases(userToken, createdPageId);
                    }
                    createdCount++;
                }
            } catch (Exception e) {
                failedKeys.add(template.key());
                failedDetails.add(template.key() + ": " + e.getMessage());
                log.error("템플릿 생성 중 오류 발생 - Key: {}, Error: {}", template.key(), e.getMessage());
            }
        }

        // 모든 페이지 생성 후 루트 페이지에 페이지 멘션 콜아웃 append
        if (rootCreatedPageId != null) {
            try {
                appendNavCalloutsToRootPage(userToken, rootCreatedPageId, pageKeyToIdMap);
            } catch (Exception e) {
                log.warn("네비게이션 콜아웃 추가 실패 (무시): {}", e.getMessage());
            }
        }

        if (createdCount == 0) {
            String detail = failedDetails.isEmpty()
                    ? "노션 토큰과 부모 페이지 연결 권한을 확인해주세요."
                    : String.join(" / ", failedDetails);
            throw new RuntimeException("노션 페이지가 생성되지 않았습니다. " + detail);
        }

        if (!failedKeys.isEmpty()) {
            throw new RuntimeException("일부 노션 페이지 생성에 실패했습니다: " + String.join(" / ", failedDetails));
        }
    }

    /**
     * 모든 하위 페이지 생성 후, 루트 페이지에 페이지 멘션이 담긴 콜아웃 블록을 추가합니다.
     */
    private void appendNavCalloutsToRootPage(String token, String rootPageId, Map<String, String> pageKeyToIdMap) {
        List<Map<String, Object>> blocks = new ArrayList<>();

        // 기획/개발/DB 콜아웃
        List<Map<String, Object>> leftRichText = buildMentionList(pageKeyToIdMap,
                new String[]{"기획", "개발", "DB", "db"});
        if (!leftRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(leftRichText, "📂", "gray_background"));
        }

        // 그라운드룰 콜아웃
        List<Map<String, Object>> groundRuleRichText = buildMentionList(pageKeyToIdMap,
                new String[]{"그라운드룰", "groundrule", "ground"});
        if (!groundRuleRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(groundRuleRichText, "🔗", "gray_background"));
        }

        // 역할별 가이드 콜아웃
        List<Map<String, Object>> roleGuideRichText = buildMentionList(pageKeyToIdMap,
                new String[]{"역할별", "가이드", "role"});
        if (!roleGuideRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(roleGuideRichText, "😀", "gray_background"));
        }

        if (blocks.isEmpty()) return;

        Map<String, Object> body = Map.of("children", blocks);
        try {
            restTemplate.exchange(
                    "https://api.notion.com/v1/blocks/" + rootPageId + "/children",
                    org.springframework.http.HttpMethod.PATCH,
                    new HttpEntity<>(body, createNotionHeaders(token)),
                    Map.class
            );
            log.info("루트 페이지에 네비게이션 콜아웃 블록 추가 완료");
        } catch (RestClientResponseException e) {
            log.error("네비게이션 콜아웃 append 실패 status={} body={}", e.getStatusCode(), summarizeResponseBody(e.getResponseBodyAsString()));
            throw new RuntimeException("nav callout append failed", e);
        }
    }

    /**
     * pageKeyToIdMap에서 키워드와 매칭되는 페이지들의 멘션 rich_text 리스트를 만듭니다.
     * 여러 멘션 사이에는 줄바꿈 텍스트를 삽입합니다.
     */
    private List<Map<String, Object>> buildMentionList(Map<String, String> pageKeyToIdMap, String[] keywords) {
        List<Map<String, Object>> richText = new ArrayList<>();
        for (String keyword : keywords) {
            for (Map.Entry<String, String> entry : pageKeyToIdMap.entrySet()) {
                String key = entry.getKey().toLowerCase();
                if (key.contains(keyword.toLowerCase())) {
                    if (!richText.isEmpty()) {
                        richText.add(Map.of("type", "text", "text", Map.of("content", "\n")));
                    }
                    richText.add(Map.of(
                            "type", "mention",
                            "mention", Map.of("type", "page", "page", Map.of("id", entry.getValue()))
                    ));
                    break;
                }
            }
        }
        return richText;
    }

    private Map<String, Object> createMentionCalloutBlock(List<Map<String, Object>> richText, String emoji, String color) {
        return Map.of(
                "object", "block",
                "type", "callout",
                "callout", Map.of(
                        "rich_text", richText,
                        "icon", Map.of("type", "emoji", "emoji", emoji),
                        "color", color
                )
        );
    }

    /**
     * 노션 URL에서 32자리 Page ID를 추출하는 유틸리티 메서드
     */
    public String extractPageId(String url) {
        if (url == null || url.isEmpty()) return null;

        // 물음표(?) 뒤의 파라미터는 무시하고 32자리 또는 하이픈 포함 UUID를 찾음
        Pattern pattern = Pattern.compile(
                "([a-fA-F0-9]{32}|[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})"
        );

        // URL에서 쿼리 스트링 제거
        String cleanUrl = url.split("\\?")[0];
        Matcher matcher = pattern.matcher(cleanUrl);

        if (matcher.find()) {
            return matcher.group(1).replace("-", "");
        }
        return null;
    }

    private String callNotionApi(String token, String parentId, AiResponseDto.TemplateDto template, String projectSubject, String projectSummary, List<MemberDto.InfoResponse> members) {
        String url = "https://api.notion.com/v1/pages";
        HttpHeaders headers = createNotionHeaders(token);

        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("page_id", parentId));
        body.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of("text", Map.of("content", template.title()))))
        ));

        List<Map<String, Object>> children = new ArrayList<>();
        addDashboardIntroBlocks(template, children, projectSubject, projectSummary, members);
        if (!isStructuralPage(template)) {
            parseContentToBlocks(template.content(), children);
        }

        if (!children.isEmpty()) {
            body.put("children", children);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("id");
            }
            throw new RuntimeException("Notion API returned " + response.getStatusCode());
        } catch (RestClientResponseException e) {
            String responseBody = summarizeResponseBody(e.getResponseBodyAsString());
            log.error("노션 API 호출 실패 status={} body={}", e.getStatusCode(), responseBody);
            throw new RuntimeException("Notion API 호출 실패: " + e.getStatusCode() + " " + responseBody, e);
        } catch (Exception e) {
            log.error("노션 API 호출 실패: {}", e.getMessage());
            throw new RuntimeException("Notion API 호출 실패: " + e.getMessage(), e);
        }
    }

    private String summarizeResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compactBody = body.replaceAll("\\s+", " ").trim();
        return compactBody.length() > 500 ? compactBody.substring(0, 500) + "..." : compactBody;
    }

    private void createDashboardDatabases(String token, String parentPageId) {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();

        String meetingDatabaseId = createDatabase(token, parentPageId, "회의록", Map.of(
                "구분", Map.of("title", Map.of()),
                "날짜", Map.of("date", Map.of()),
                "방식", Map.of("select", Map.of("options", List.of(
                        selectOption("온라인", "blue"),
                        selectOption("오프라인", "brown"),
                        selectOption("하이브리드", "purple")
                ))),
                "URL", Map.of("url", Map.of()),
                "참여자", Map.of("rich_text", Map.of())
        ));
        createDatabasePage(token, meetingDatabaseId, Map.of(
                "구분", titleProperty("첫번째 회의"),
                "날짜", dateProperty(today),
                "방식", selectProperty("오프라인"),
                "참여자", richTextProperty("")
        ));

        String scheduleDatabaseId = createDatabase(token, parentPageId, "일정", Map.of(
                "일정", Map.of("title", Map.of()),
                "날짜", Map.of("date", Map.of()),
                "상태", Map.of("select", Map.of("options", List.of(
                        selectOption("예정", "gray"),
                        selectOption("진행중", "blue"),
                        selectOption("완료", "green")
                ))),
                "담당", Map.of("rich_text", Map.of()),
                "메모", Map.of("rich_text", Map.of())
        ));
        createDatabasePage(token, scheduleDatabaseId, Map.of(
                "일정", titleProperty("프로젝트 킥오프"),
                "날짜", dateProperty(today),
                "상태", selectProperty("예정"),
                "담당", richTextProperty("전체"),
                "메모", richTextProperty("초기 목표와 역할을 정리합니다.")
        ));
    }

    private String createDatabase(String token, String parentPageId, String title, Map<String, Object> properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", Map.of("type", "page_id", "page_id", parentPageId));
        body.put("title", List.of(textObject(title)));
        body.put("properties", properties);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.notion.com/v1/databases",
                    new HttpEntity<>(body, createNotionHeaders(token)),
                    Map.class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object id = response.getBody().get("id");
                if (id != null) {
                    return id.toString();
                }
            }
            throw new RuntimeException("Notion database API returned " + response.getStatusCode());
        } catch (RestClientResponseException e) {
            String responseBody = summarizeResponseBody(e.getResponseBodyAsString());
            log.error("Notion database creation failed status={} body={}", e.getStatusCode(), responseBody);
            throw new RuntimeException("Notion database creation failed: " + e.getStatusCode() + " " + responseBody, e);
        }
    }

    private void createDatabasePage(String token, String databaseId, Map<String, Object> properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", Map.of("database_id", databaseId));
        body.put("properties", properties);

        try {
            restTemplate.postForEntity(
                    "https://api.notion.com/v1/pages",
                    new HttpEntity<>(body, createNotionHeaders(token)),
                    Map.class
            );
        } catch (RestClientResponseException e) {
            String responseBody = summarizeResponseBody(e.getResponseBodyAsString());
            log.error("Notion database row creation failed status={} body={}", e.getStatusCode(), responseBody);
            throw new RuntimeException("Notion database row creation failed: " + e.getStatusCode() + " " + responseBody, e);
        }
    }

    private HttpHeaders createNotionHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Notion-Version", "2022-06-28");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private String extractTitle(Map<?, ?> page) {
        Object propertiesObject = page.get("properties");
        if (propertiesObject instanceof Map<?, ?> properties) {
            for (Object propertyObject : properties.values()) {
                if (propertyObject instanceof Map<?, ?> property && "title".equals(property.get("type"))) {
                    Object titleObject = property.get("title");
                    if (titleObject instanceof List<?> titleItems && !titleItems.isEmpty()) {
                        Object first = titleItems.get(0);
                        if (first instanceof Map<?, ?> richText) {
                            Object plainText = richText.get("plain_text");
                            if (plainText != null && !plainText.toString().isBlank()) {
                                return plainText.toString();
                            }
                        }
                    }
                }
            }
        }
        return "Untitled";
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void addDashboardIntroBlocks(AiResponseDto.TemplateDto template, List<Map<String, Object>> blocks, String projectSubject, String projectSummary, List<MemberDto.InfoResponse> members) {
        if (template.parentKey() == null) {
            addRootPageIntroBlocks(blocks, projectSummary, members);
        } else if (isKeyMatch(template, "기획")) {
            addPlanningPageIntroBlocks(blocks, projectSummary);
        } else if (isKeyMatch(template, "개발")) {
            addDevPageIntroBlocks(blocks);
        }
    }

    private boolean isKeyMatch(AiResponseDto.TemplateDto template, String keyword) {
        return (template.key() != null && template.key().contains(keyword))
                || (template.title() != null && template.title().contains(keyword));
    }

    private boolean isStructuralPage(AiResponseDto.TemplateDto template) {
        return template.parentKey() == null
                || isKeyMatch(template, "기획")
                || isKeyMatch(template, "개발");
    }

    private void addRootPageIntroBlocks(List<Map<String, Object>> blocks, String projectSummary, List<MemberDto.InfoResponse> members) {
        String summaryBody = (projectSummary != null && !projectSummary.isBlank())
                ? projectSummary
                : "세부 내용이 등록되지 않았습니다.";

        blocks.add(createCalloutBlock(summaryBody, "📌", "gray_background"));
        blocks.add(createHeadingBlock("팀원 정보"));
        blocks.add(createTeamInfoTableBlock(members));
        // 기획/개발/DB, 그라운드룰/역할별 가이드 콜아웃은
        // 하위 페이지 생성 후 page mention으로 appendNavCalloutsToRootPage에서 추가됩니다.
    }

    private void addPlanningPageIntroBlocks(List<Map<String, Object>> blocks, String projectSummary) {
        String summary = (projectSummary != null && !projectSummary.isBlank())
                ? projectSummary
                : "프로젝트 한 줄 소개를 입력하세요.";
        blocks.add(createCalloutBlock(summary, "📌", "gray_background"));
    }

    private void addDevPageIntroBlocks(List<Map<String, Object>> blocks) {
        blocks.add(createCalloutBlock("메뉴얼", "💡", "yellow_background"));
        blocks.add(createCalloutBlock("Backend\nFrontend", "⌨️", "gray_background"));
    }

    private String extractSummaryLine(Object content) {
        List<String> snippets = new ArrayList<>();
        collectPlainText(content, snippets);
        return snippets.stream()
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .findFirst()
                .map(text -> text.length() > 180 ? text.substring(0, 180) : text)
                .orElse("");
    }

    private void collectPlainText(Object content, List<String> snippets) {
        if (content == null || snippets.size() >= 3) {
            return;
        }
        if (content instanceof String text) {
            if (!text.trim().isBlank()) {
                snippets.add(text.trim());
            }
            return;
        }
        if (content instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collectPlainText(value, snippets);
                if (snippets.size() >= 3) {
                    return;
                }
            }
            return;
        }
        if (content instanceof List<?> list) {
            for (Object item : list) {
                collectPlainText(item, snippets);
                if (snippets.size() >= 3) {
                    return;
                }
            }
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
                "paragraph", Map.of("rich_text", List.of(textObject(text))));
    }

    private Map<String, Object> createBulletBlock(String text) {
        return Map.of("object", "block", "type", "bulleted_list_item",
                "bulleted_list_item", Map.of("rich_text", List.of(textObject(text))));
    }

    private Map<String, Object> createCalloutBlock(String text, String emoji, String color) {
        return Map.of(
                "object", "block",
                "type", "callout",
                "callout", Map.of(
                        "rich_text", List.of(textObject(text)),
                        "icon", Map.of("type", "emoji", "emoji", emoji),
                        "color", color
                )
        );
    }

    private Map<String, Object> createCalloutBlock(String title, String body, String emoji, String color) {
        List<Map<String, Object>> richText = new ArrayList<>();
        richText.add(textObject(title + "\n"));
        richText.add(textObject(body));
        return Map.of(
                "object", "block",
                "type", "callout",
                "callout", Map.of(
                        "rich_text", richText,
                        "icon", Map.of("type", "emoji", "emoji", emoji),
                        "color", color
                )
        );
    }

    private Map<String, Object> createTeamInfoTableBlock(List<MemberDto.InfoResponse> members) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(tableRow("이름", "포지션", "이메일", "연락처"));

        if (members != null && !members.isEmpty()) {
            for (MemberDto.InfoResponse member : members) {
                String name = member.getName() != null ? member.getName() : "";
                String role = member.getRole() != null ? member.getRole() : "";
                String email = member.getEmail() != null ? member.getEmail() : "";
                String contact = ""; // DB에 연락처 필드가 없으므로 빈 문자열 처리
                
                rows.add(tableRow(name, role, email, contact));
            }
        } else {
            rows.add(tableRow("", "", "", ""));
        }

        return Map.of(
                "object", "block",
                "type", "table",
                "table", Map.of(
                        "table_width", 4,
                        "has_column_header", true,
                        "has_row_header", false,
                        "children", rows
                )
        );
    }

    private Map<String, Object> tableRow(String... cells) {
        List<List<Map<String, Object>>> rowCells = new ArrayList<>();
        for (String cell : cells) {
            rowCells.add(List.of(textObject(cell)));
        }
        return Map.of(
                "object", "block",
                "type", "table_row",
                "table_row", Map.of("cells", rowCells)
        );
    }

    private Map<String, Object> createDividerBlock() {
        return Map.of("object", "block", "type", "divider", "divider", Map.of());
    }

    private Map<String, Object> textObject(String content) {
        return textObject(content, Map.of());
    }

    private Map<String, Object> textObject(String content, Map<String, Object> annotations) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", Map.of("content", truncateRichText(content)));
        if (!annotations.isEmpty()) {
            text.put("annotations", annotations);
        }
        return text;
    }

    private String truncateRichText(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 1900 ? content.substring(0, 1900) : content;
    }

    private Map<String, Object> selectOption(String name, String color) {
        return Map.of("name", name, "color", color);
    }

    private Map<String, Object> titleProperty(String value) {
        return Map.of("title", List.of(textObject(value)));
    }

    private Map<String, Object> richTextProperty(String value) {
        if (value == null || value.isBlank()) {
            return Map.of("rich_text", List.of());
        }
        return Map.of("rich_text", List.of(textObject(value)));
    }

    private Map<String, Object> dateProperty(String date) {
        return Map.of("date", Map.of("start", date));
    }

    private Map<String, Object> selectProperty(String value) {
        return Map.of("select", Map.of("name", value));
    }
}
