package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.NotionOAuthDto;
import com.example.MatchaTonic.Back.entity.manual.Manual;
import com.example.MatchaTonic.Back.repository.ManualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final ManualRepository manualRepository;

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
    public void createProjectPagesOnNotion(AiResponseDto aiResponse, String userToken, String pageUrl, String projectSubject, String projectSummary, List<MemberDto.InfoResponse> members, Map<String, Object> collectedData) {
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
        String rootCreatedPageId = null;   // 홈 페이지 ID (딱 한 번만 설정)
        Set<String> rootLevelKeys = new HashSet<>(); // nav 콜아웃에 표시할 직계 자식 키

        // 구조적 부모 키 모음 (기획/개발 하위 AI 템플릿 skip 용)
        Set<String> structuralParentKeys = new HashSet<>();
        for (AiResponseDto.TemplateDto t : aiResponse.templates()) {
            if (isKeyMatch(t, "기획") || isKeyMatch(t, "PLANNING")
                    || isKeyMatch(t, "개발") || isKeyMatch(t, "DEVELOPMENT")) {
                structuralParentKeys.add(t.key());
            }
        }

        for (AiResponseDto.TemplateDto template : aiResponse.templates()) {
            try {
                // ① 기획/개발의 AI 하위 템플릿은 우리가 직접 만들므로 SKIP
                if (template.parentKey() != null && structuralParentKeys.contains(template.parentKey())) {
                    log.info("구조적 페이지 하위 AI 템플릿 skip: key={}", template.key());
                    continue;
                }

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
                        // ② 홈 페이지(Project Home)만 회의록/일정 DB 생성, rootCreatedPageId 설정
                        if (rootCreatedPageId == null && isRootHomePage(template)) {
                            rootCreatedPageId = createdPageId;
                            createDashboardDatabases(userToken, createdPageId);
                        }
                        // nav 콜아웃 대상 (직계 자식)에 추가 — 홈 페이지 자신은 제외
                        if (!isRootHomePage(template)) {
                            rootLevelKeys.add(template.key());
                        }
                    } else if (isKeyMatch(template, "기획") || isKeyMatch(template, "PLANNING")) {
                        createPlanningSubPages(userToken, createdPageId, collectedData);
                    } else if (isKeyMatch(template, "개발") || isKeyMatch(template, "DEVELOPMENT")) {
                        createDevelopmentSubPages(userToken, createdPageId, collectedData);
                    } else if (isKeyMatch(template, "역할별") || isKeyMatch(template, "ROLE_GUIDE") || isKeyMatch(template, "role_guide") || isKeyMatch(template, "가이드")) {
                        createManualSubPages(userToken, createdPageId, "PLAN");
                    } else if (isKeyMatch(template, "DB") || isKeyMatch(template, "db")) {
                        createDbSchemaDatabase(userToken, createdPageId);
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
        log.info("생성된 페이지 키 목록: {}", pageKeyToIdMap.keySet());
        if (rootCreatedPageId != null) {
            try {
                appendNavCalloutsToRootPage(userToken, rootCreatedPageId, pageKeyToIdMap, aiResponse.templates(), rootLevelKeys);
            } catch (Exception e) {
                log.warn("네비게이션 콜아웃 추가 실패: {}", e.getMessage());
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
     * templates 리스트로 key뿐 아니라 title까지 매칭합니다.
     */
    private void appendNavCalloutsToRootPage(String token, String rootPageId,
            Map<String, String> pageKeyToIdMap, List<AiResponseDto.TemplateDto> templates,
            Set<String> rootLevelKeys) {

        // rootLevelKeys로 필터: 홈 직계 자식만 nav에 표시
        Map<String, String> filteredMap = new HashMap<>();
        for (Map.Entry<String, String> e : pageKeyToIdMap.entrySet()) {
            if (rootLevelKeys.contains(e.getKey())) {
                filteredMap.put(e.getKey(), e.getValue());
            }
        }
        log.info("[NavCallout] rootLevelKeys: {}", rootLevelKeys);
        log.info("[NavCallout] filteredMap keys: {}", filteredMap.keySet());

        // key -> title 역방향 맵 구성 (매칭 편의용)
        Map<String, String> keyToTitle = new HashMap<>();
        for (AiResponseDto.TemplateDto t : templates) {
            if (t.key() != null && t.title() != null) {
                keyToTitle.put(t.key(), t.title());
            }
        }
        log.info("[NavCallout] pageKeyToIdMap keys: {}", pageKeyToIdMap.keySet());
        log.info("[NavCallout] keyToTitle: {}", keyToTitle);

        List<Map<String, Object>> blocks = new ArrayList<>();

        // 기획/개발/DB 콜아웃 (직계 자식 중에서만)
        List<Map<String, Object>> leftRichText = buildMentionListByTitleOrKey(
                filteredMap, keyToTitle,
                new String[]{"기획", "개발", "DB", "db", "planning", "plan", "development", "dev"});
        if (!leftRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(leftRichText, "📂", "gray_background"));
        } else {
            log.warn("[NavCallout] 기획/개발/DB 매칭 실패 - 키 목록: {}", filteredMap.keySet());
        }

        // 그라운드룰 콜아웃 (직계 자식 중에서만)
        List<Map<String, Object>> groundRuleRichText = buildMentionListByTitleOrKey(
                filteredMap, keyToTitle,
                new String[]{"그라운드룰", "ground_rules", "groundrule", "ground", "rule"});
        if (!groundRuleRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(groundRuleRichText, "🔗", "gray_background"));
        }

        // 역할별 가이드 콜아웃 (직계 자식 중에서만)
        List<Map<String, Object>> roleGuideRichText = buildMentionListByTitleOrKey(
                filteredMap, keyToTitle,
                new String[]{"역할별", "가이드", "role_guide", "role", "guide"});
        if (!roleGuideRichText.isEmpty()) {
            blocks.add(createMentionCalloutBlock(roleGuideRichText, "😀", "gray_background"));
        }

        if (blocks.isEmpty()) {
            log.warn("[NavCallout] 추가할 콜아웃 블록이 없습니다. 매칭 실패.");
            return;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            String jsonBody = objectMapper.writeValueAsString(Map.of("children", blocks));

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.notion.com/v1/blocks/" + rootPageId + "/children"))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", "2022-06-28")
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[NavCallout] 루트 페이지에 네비게이션 콜아웃 블록 추가 완료 (status={})", response.statusCode());
            } else {
                log.error("[NavCallout] append 실패 status={} body={}", response.statusCode(), summarizeResponseBody(response.body()));
                throw new RuntimeException("nav callout append failed: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            log.error("[NavCallout] append 요청 중 오류: {}", e.getMessage());
            throw new RuntimeException("nav callout append failed", e);
        }
    }

    /**
     * key 또는 title에 키워드가 포함되는 페이지를 찾아 멘션 rich_text 리스트를 만듭니다.
     */
    private List<Map<String, Object>> buildMentionListByTitleOrKey(
            Map<String, String> pageKeyToIdMap,
            Map<String, String> keyToTitle,
            String[] keywords) {

        List<Map<String, Object>> richText = new ArrayList<>();
        Set<String> addedKeys = new HashSet<>();

        for (String keyword : keywords) {
            String lowerKw = keyword.toLowerCase();
            for (Map.Entry<String, String> entry : pageKeyToIdMap.entrySet()) {
                String mapKey = entry.getKey();
                if (addedKeys.contains(mapKey)) continue;

                String lowerKey = mapKey.toLowerCase();
                String lowerTitle = keyToTitle.getOrDefault(mapKey, "").toLowerCase();

                if (lowerKey.contains(lowerKw) || lowerTitle.contains(lowerKw)) {
                    if (!richText.isEmpty()) {
                        richText.add(Map.of("type", "text", "text", Map.of("content", "\n")));
                    }
                    richText.add(Map.of(
                            "type", "mention",
                            "mention", Map.of("type", "page", "page", Map.of("id", entry.getValue()))
                    ));
                    addedKeys.add(mapKey);
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

        // "01. 기획", "1. 개발" 같은 숫자 prefix 제거
        String cleanTitle = template.title() != null
                ? template.title().replaceAll("^\\d+\\.\\s*", "").trim()
                : "Untitled";

        String pageEmoji = resolvePageEmoji(template);

        Map<String, Object> body = new HashMap<>();
        body.put("parent", Map.of("page_id", parentId));
        body.put("icon", Map.of("type", "emoji", "emoji", pageEmoji));
        body.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of("text", Map.of("content", cleanTitle))))
        ));

        List<Map<String, Object>> children = new ArrayList<>();
        addDashboardIntroBlocks(template, children, projectSubject, projectSummary, members);
        if (!isStructuralPage(template)) {
            parseContentToBlocks(template.content(), children);
        }

        if (!children.isEmpty()) {
            body.put("children", children);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, createNotionHeaders(token));
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

    /**
     * 기획 페이지 안에 고정 하위 페이지들을 DB 데이터로 채워 생성합니다.
     */
    private void createPlanningSubPages(String token, String planningPageId, Map<String, Object> data) {
        log.info("[Planning] collectedData keys: {}, values: {}", data != null ? data.keySet() : "null", data);

        String subject  = asStringFromData(data, "subject", "");
        String goal     = asStringFromData(data, "goal", "");
        String roles    = asStringFromData(data, "roles", "");
        String problem  = asStringFromData(data, "problem", asStringFromData(data, "문제", ""));
        String solution = asStringFromData(data, "solution", asStringFromData(data, "솔루션", ""));
        String persona  = asStringFromData(data, "targetPersona", asStringFromData(data, "persona", asStringFromData(data, "페르소나", "")));
        String market   = asStringFromData(data, "market", asStringFromData(data, "marketAnalysis", asStringFromData(data, "시장", "")));
        String usp      = asStringFromData(data, "usp", asStringFromData(data, "differentiator", asStringFromData(data, "차별점", "")));

        // 문제 정의: problem > subject > 기본 안내
        String problemContent = !problem.isBlank() ? problem
                : !subject.isBlank() ? subject
                : "## 우리가 해결하려는 문제\n\n- 문제 상황:\n- 문제의 원인:\n- 문제를 겪는 대상:\n\n## 현재 상황 (As-Is)\n\n(현재 상태를 작성하세요)\n\n## 원하는 상황 (To-Be)\n\n(개선 목표를 작성하세요)";

        // 솔루션: solution > goal > 기본 안내
        String solutionContent = !solution.isBlank() ? solution
                : !goal.isBlank() ? goal
                : "## 핵심 솔루션\n\n- 솔루션 요약:\n\n## 주요 기능\n\n- 기능 1:\n- 기능 2:\n- 기능 3:\n\n## 기대 효과\n\n- ";

        // 타겟 페르소나: persona > 기본 안내
        String personaContent = !persona.isBlank() ? persona
                : "## 주요 타겟 사용자\n\n- 연령/직군:\n- 주요 고민:\n- 행동 패턴:\n\n## 사용자 Pain Point\n\n- \n- \n\n## 사용자 Goal\n\n- ";

        // 시장/경쟁사: market > 기본 안내
        String marketContent = !market.isBlank() ? market
                : "## 시장 규모 & 트렌드\n\n- 시장 규모:\n- 성장 트렌드:\n\n## 경쟁사 분석\n\n| 경쟁사 | 강점 | 약점 |\n| --- | --- | --- |\n| | | |\n\n## 기회 요인\n\n- ";

        // 우리의 차별점: usp > roles > 기본 안내
        String uspContent = !usp.isBlank() ? usp
                : !roles.isBlank() ? roles
                : "## 핵심 차별점 (USP)\n\n- 차별점 1:\n- 차별점 2:\n- 차별점 3:\n\n## 경쟁사 대비 우위\n\n- \n\n## 우리만의 가치 제안\n\n- ";

        createPageWithContent(token, planningPageId, "문제 정의 (Problem)", "🗂️", problemContent);
        createPageWithContent(token, planningPageId, "솔루션 (Solution)", "👋", solutionContent);
        createPageWithContent(token, planningPageId, "타겟 페르소나 (Target Persona)", "👤", personaContent);
        createPageWithContent(token, planningPageId, "시장 / 경쟁사 분석 (Market & Competitor)", "📊", marketContent);
        createPageWithContent(token, planningPageId, "우리의 차별점 (USP)", "⭐", uspContent);

        log.info("기획 하위 페이지 생성 완료");
    }

    /**
     * 개발 페이지 안에 고정 하위 페이지들을 생성하고,
     * Backend/Frontend 페이지 안에 Manual DB 내용을 하위 페이지로 넣습니다.
     */
    private void createDevelopmentSubPages(String token, String developmentPageId, Map<String, Object> data) {
        String projectName  = asStringFromData(data, "projectName", asStringFromData(data, "title", "프로젝트"));
        String subject      = asStringFromData(data, "subject", "");
        String goal         = asStringFromData(data, "goal", "");
        String deliverables = asStringFromData(data, "deliverables", "");
        String dueDate      = asStringFromData(data, "dueDate", "");

        // README.md
        String readmeContent = "## 프로젝트 소개\n"
                + (subject.isBlank() ? "(프로젝트 소개를 입력하세요)" : subject) + "\n\n"
                + "## 주요 기능\n"
                + (goal.isBlank() ? "- " : "- " + goal) + "\n\n"
                + "## 기술 스택\n"
                + "- Frontend:\n"
                + "- Backend:\n"
                + "- Infra / Tools:\n\n"
                + "## 실행 방법 (How to Run)\n"
                + "```bash\n# 의존성 설치\nnpm install\n\n# 개발 서버 실행\nnpm run dev\n```\n\n"
                + "## 폴더 구조\n"
                + "/\n├─ src\n├─ docs\n└─ README.md\n\n"
                + "## 기여 방법 (Contribution Guide)\n"
                + "- 브랜치를 생성하고 PR을 통해 기여해주세요.\n"
                + (deliverables.isBlank() ? "" : "\n## 주요 산출물\n" + deliverables)
                + (dueDate.isBlank() ? "" : "\n\n## 마감일\n" + dueDate);
        createPageWithContent(token, developmentPageId, "README.md", "📄", readmeContent);

        // Git Convention (고정)
        createPageWithContent(token, developmentPageId, "Git Convention", "📄",
                "## 브랜치 전략\n- main: 배포 브랜치\n- develop: 개발 통합 브랜치\n- feature/{기능명}: 기능 개발\n- fix/{버그명}: 버그 수정\n\n"
                + "## 커밋 메시지 규칙\n- feat: 새로운 기능\n- fix: 버그 수정\n- docs: 문서 수정\n- style: 코드 포맷\n- refactor: 리팩토링\n- test: 테스트\n- chore: 빌드/설정 변경");

        // PR Template
        createPageWithContent(token, developmentPageId, "PR(Pull Request) Template", "📄",
                "## 변경 사항\n- \n\n## 관련 이슈\n- closes #\n\n## 테스트 항목\n- [ ] \n\n## 리뷰어에게\n");

        // 버전 관리 정책
        createPageWithContent(token, developmentPageId, "버전 관리 정책 (Versioning)", "📄",
                "## 버전 규칙\n"
                + "vMAJOR.MINOR.PATCH\n\n"
                + "- MAJOR: 기존 기능과 호환되지 않는 큰 변경\n"
                + "- MINOR: 기능 추가 (하위 호환 유지)\n"
                + "- PATCH: 버그 수정, 작은 개선\n\n"
                + "## 버전 업데이트 기준\n"
                + "- 기능 추가 → MINOR 증가\n"
                + "- 버그 수정 → PATCH 증가\n"
                + "- 구조/정책 변경 → MAJOR 증가\n\n"
                + "## 릴리즈 메모 작성 규칙\n"
                + "- 변경 사항 요약\n"
                + "- 주요 기능 추가 여부\n"
                + "- 주의사항 (Breaking Change)");

        // Backend 페이지 → Manual DB(BACKEND) 우선, 없으면 하드코딩 템플릿
        String backendPageId = createPageWithContentAndReturnId(token, developmentPageId, "Backend", "📄", null);
        if (backendPageId != null) {
            // BACKEND + DEV(공통) 카테고리 합산
            List<Manual> backendManuals = new ArrayList<>();
            backendManuals.addAll(manualRepository.findByTargetAndCategoryContainingOrderByStepOrderAsc("DEV", "BACKEND"));
            backendManuals.addAll(manualRepository.findByTargetAndCategoryContainingOrderByStepOrderAsc("DEV", "DEV"));
            if (!backendManuals.isEmpty()) {
                for (Manual m : backendManuals) createPageWithContent(token, backendPageId, m.getTitle(), "📄", m.getContent());
            } else {
                createBackendTemplateSubPages(token, backendPageId);
            }
        }

        // Frontend 페이지 → Manual DB(FRONTEND) 우선, 없으면 하드코딩 템플릿
        String frontendPageId = createPageWithContentAndReturnId(token, developmentPageId, "Frontend", "📄", null);
        if (frontendPageId != null) {
            List<Manual> frontendManuals = manualRepository.findByTargetAndCategoryContainingOrderByStepOrderAsc("DEV", "FRONTEND");
            if (!frontendManuals.isEmpty()) {
                for (Manual m : frontendManuals) createPageWithContent(token, frontendPageId, m.getTitle(), "📄", m.getContent());
            } else {
                createFrontendTemplateSubPages(token, frontendPageId);
            }
        }

        log.info("개발 하위 페이지 생성 완료");
    }

    /**
     * 아이콘과 내용이 있는 하위 페이지를 생성합니다. content가 null이면 빈 페이지.
     */
    private void createPageWithContent(String token, String parentId, String title, String emoji, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", Map.of("page_id", parentId));
        body.put("icon", Map.of("type", "emoji", "emoji", emoji));
        body.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of("text", Map.of("content", title))))
        ));
        if (content != null && !content.isBlank()) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (String line : content.split("\n")) {
                if (line.startsWith("## ")) {
                    children.add(Map.of("object", "block", "type", "heading_2",
                            "heading_2", Map.of("rich_text", List.of(textObject(line.substring(3))))));
                } else if (line.startsWith("### ")) {
                    children.add(Map.of("object", "block", "type", "heading_3",
                            "heading_3", Map.of("rich_text", List.of(textObject(line.substring(4))))));
                } else if (line.startsWith("- ")) {
                    children.add(createBulletBlock(line.substring(2)));
                } else if (line.startsWith("- [ ] ")) {
                    children.add(createBulletBlock("☐ " + line.substring(6)));
                } else if (!line.isBlank()) {
                    children.add(createParagraphBlock(line));
                }
            }
            if (!children.isEmpty()) body.put("children", children);
        }
        try {
            restTemplate.postForEntity(
                    "https://api.notion.com/v1/pages",
                    new HttpEntity<>(body, createNotionHeaders(token)),
                    Map.class
            );
        } catch (RestClientResponseException e) {
            log.error("하위 페이지 생성 실패 title={} status={}", title, e.getStatusCode());
        }
    }

    private void createFrontendTemplateSubPages(String token, String parentId) {
        createPageWithContent(token, parentId, "🧰 기술 스택 & 버전 (Tech Stack)", "📄",
                "## 🧰 기술 스택 & 버전 (Tech Stack)\n"
                + "프론트엔드 개발 환경을 명확히 합니다.\n\n"
                + "- Framework / Library: (예: React 18 / Next.js 14 / Vue 3 등)\n"
                + "- Language: (예: TypeScript 5.x)\n"
                + "- State Management: (예: Redux / Zustand / Recoil 등)\n"
                + "- Styling: (예: CSS Module / Tailwind / Styled-components 등)\n"
                + "- Build / Tooling: (예: Vite / Webpack / ESLint / Prettier 등)");

        createPageWithContent(token, parentId, "🗂 폴더 구조 (Folder Structure)", "📄",
                "## 🗂 폴더 구조 (Folder Structure)\n"
                + "프로젝트 전반 구조를 한눈에 파악할 수 있도록 작성합니다.\n\n"
                + "src/\n"
                + " ├─ assets/\n"
                + " ├─ components/\n"
                + " ├─ pages/\n"
                + " ├─ hooks/\n"
                + " ├─ services/\n"
                + " ├─ styles/\n"
                + " ├─ utils/\n"
                + " └─ main.tsx\n\n"
                + "(실제 프로젝트 구조에 맞게 수정 가능)");

        createPageWithContent(token, parentId, "🧭 라우팅 맵 (Routing Map)", "📄",
                "## 🧭 라우팅 맵 (Routing Map)\n"
                + "주요 화면과 URL 구조를 정의합니다.\n\n"
                + "| Route | 페이지 설명 | 권한 |\n"
                + "| / | 메인 페이지 | Public |\n"
                + "| /login | 로그인 | Public |\n"
                + "| /dashboard | 대시보드 | Auth |\n"
                + "| /settings | 설정 | Auth |");

        createPageWithContent(token, parentId, "🔐 환경 변수 가이드 (Environment Variables)", "📄",
                "## 🔐 환경 변수 가이드 (Environment Variables)\n"
                + "개발·배포 환경에서 사용하는 환경 변수를 정리합니다.\n\n"
                + "| 변수명 | 설명 | 사용 환경 |\n"
                + "| VITE_API_BASE_URL | 백엔드 API 주소 | Dev / Prod |\n"
                + "| VITE_ENV | 실행 환경 | Dev / Prod |\n\n"
                + "- .env.local 파일 사용\n"
                + "- 민감 정보는 Git에 커밋 금지");

        createPageWithContent(token, parentId, "🔗 백엔드 API 연동", "📄",
                "## 🔗 백엔드 API 연동\n"
                + "프론트엔드와 백엔드 연동 여부를 명시합니다.\n\n"
                + "- 연동 여부: ☐ 연동함  ☐ 연동 안 함\n"
                + "- API Base URL:\n"
                + "- 연동 방식: (예: REST / GraphQL / WebSocket)\n"
                + "- 에러 처리 방식:");

        createPageWithContent(token, parentId, "🎨 UX / UI 컴포넌트 관리", "📄",
                "## 🎨 UX / UI 컴포넌트 관리\n"
                + "디자인 및 UI 컴포넌트 개발 여부를 관리합니다.\n\n"
                + "- 디자인 시스템 사용 여부: ☐ 사용  ☐ 미사용\n"
                + "- 공통 컴포넌트 개발 여부: ☐ O  ☐ X\n"
                + "- 컴포넌트 관리 방식: ☐ 코드 기반  ☐ 컴포넌트 DB (Notion)");
    }

    private void createBackendTemplateSubPages(String token, String parentId) {
        createPageWithContent(token, parentId, "🗂 폴더 구조 (Folder Structure)", "📄",
                "## 🗂 폴더 구조 (Folder Structure)\n"
                + "프로젝트 전반 구조를 한눈에 파악할 수 있도록 작성합니다.\n\n"
                + "src/\n"
                + " ├─ main/\n"
                + " │   ├─ java/\n"
                + " │   │   └─ com/example/project/\n"
                + " │   │       ├─ controller/\n"
                + " │   │       ├─ service/\n"
                + " │   │       ├─ repository/\n"
                + " │   │       ├─ entity/\n"
                + " │   │       └─ dto/\n"
                + " │   └─ resources/\n"
                + " └─ test/\n\n"
                + "(실제 프로젝트 구조에 맞게 수정 가능)");

        createPageWithContent(token, parentId, "🌐 도메인 정보 (Domain)", "📄",
                "## 🌐 도메인 정보 (Domain)\n"
                + "백엔드 시스템의 논리적 경계와 책임 범위를 정의합니다.\n\n"
                + "서비스 도메인 이름\n"
                + "- 도메인 명:\n"
                + "- 설명: (이 도메인이 담당하는 핵심 기능 요약)\n\n"
                + "도메인 범위 (Scope)\n"
                + "- 포함 기능:\n"
                + "- 제외 기능:");

        createPageWithContent(token, parentId, "🧱 도메인 구성 요소", "📄",
                "## 🧱 도메인 구성 요소\n"
                + "주요 비즈니스 개념과 데이터 구조를 정의합니다.\n\n"
                + "핵심 엔티티 (Entities)\n"
                + "- Entity 1\n"
                + "  - 역할:\n"
                + "  - 주요 속성:\n\n"
                + "도메인 규칙 (Business Rules)\n"
                + "- \n- ");

        createPageWithContent(token, parentId, "🔌 API 책임 요약", "📄",
                "## 🔌 API 책임 요약 (선택)\n"
                + "이 도메인이 제공하는 API의 책임 범위입니다.\n\n"
                + "- 주요 API 역할:\n"
                + "- 외부 연동 여부: ☐ 있음  ☐ 없음\n"
                + "- 연동 대상:");

        createPageWithContent(token, parentId, "👥 R&R (Roles & Responsibilities)", "📄",
                "## 👥 R&R (Roles & Responsibilities)\n"
                + "팀원별 역할과 책임을 명확히 정의합니다.\n\n"
                + "Backend 담당자\n"
                + "- 이름:\n"
                + "- 역할: (예: Backend Lead / API Developer 등)\n\n"
                + "책임 (Responsibilities)\n"
                + "- \n- \n- \n\n"
                + "권한 (Authority)\n"
                + "- ");
    }

    /**
     * Manual DB에서 target에 해당하는 매뉴얼을 가져와 하위 페이지로 생성합니다.
     */
    private void createManualSubPages(String token, String parentPageId, String target) {
        List<Manual> manuals = manualRepository.findByTargetOrderByStepOrderAsc(target);
        if (manuals.isEmpty()) {
            log.info("Manual DB에 target={} 데이터 없음", target);
            return;
        }
        for (Manual manual : manuals) {
            createPageWithContent(token, parentPageId, manual.getTitle(), "📄", manual.getContent());
        }
        log.info("Manual 하위 페이지 생성 완료 target={} count={}", target, manuals.size());
    }

    /**
     * 페이지를 생성하고 생성된 페이지 ID를 반환합니다.
     */
    private String createPageWithContentAndReturnId(String token, String parentId, String title, String emoji, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", Map.of("page_id", parentId));
        body.put("icon", Map.of("type", "emoji", "emoji", emoji));
        body.put("properties", Map.of(
                "title", Map.of("title", List.of(Map.of("text", Map.of("content", title))))
        ));
        if (content != null && !content.isBlank()) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (String line : content.split("\n")) {
                if (line.startsWith("## ")) {
                    children.add(Map.of("object", "block", "type", "heading_2",
                            "heading_2", Map.of("rich_text", List.of(textObject(line.substring(3))))));
                } else if (line.startsWith("- ")) {
                    children.add(createBulletBlock(line.substring(2)));
                } else if (!line.isBlank()) {
                    children.add(createParagraphBlock(line));
                }
            }
            if (!children.isEmpty()) body.put("children", children);
        }
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://api.notion.com/v1/pages",
                    new HttpEntity<>(body, createNotionHeaders(token)),
                    Map.class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("id");
            }
        } catch (RestClientResponseException e) {
            log.error("페이지 생성 실패 title={} status={}", title, e.getStatusCode());
        }
        return null;
    }

    private String asStringFromData(Map<String, Object> data, String key, String defaultValue) {
        if (data == null) return defaultValue;
        Object val = data.get(key);
        if (val == null || val.toString().isBlank()) return defaultValue;
        return val.toString().trim();
    }

    /**
     * DB 페이지 안에 테이블 명세 데이터베이스를 생성합니다.
     */
    private void createDbSchemaDatabase(String token, String dbPageId) {
        String schemaDbId = createDatabase(token, dbPageId, "테이블 명세", Map.of(
                "테이블명", Map.of("title", Map.of()),
                "컬럼명", Map.of("rich_text", Map.of()),
                "데이터 타입", Map.of("select", Map.of("options", List.of(
                        selectOption("VARCHAR", "blue"),
                        selectOption("INT", "green"),
                        selectOption("BIGINT", "purple"),
                        selectOption("TEXT", "yellow"),
                        selectOption("BOOLEAN", "orange"),
                        selectOption("DATETIME", "red"),
                        selectOption("DATE", "pink"),
                        selectOption("FLOAT", "gray")
                ))),
                "NULL 여부", Map.of("select", Map.of("options", List.of(
                        selectOption("NOT NULL", "red"),
                        selectOption("NULL", "gray")
                ))),
                "설명", Map.of("rich_text", Map.of())
        ));
        log.info("DB 테이블 명세 데이터베이스 생성 완료: {}", schemaDbId);

        String erDbId = createDatabase(token, dbPageId, "ERD 관계 정의", Map.of(
                "관계명", Map.of("title", Map.of()),
                "테이블 A", Map.of("rich_text", Map.of()),
                "테이블 B", Map.of("rich_text", Map.of()),
                "관계 유형", Map.of("select", Map.of("options", List.of(
                        selectOption("1:1", "blue"),
                        selectOption("1:N", "green"),
                        selectOption("N:M", "purple")
                ))),
                "설명", Map.of("rich_text", Map.of())
        ));
        log.info("ERD 관계 정의 데이터베이스 생성 완료: {}", erDbId);
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
        } else if (isKeyMatch(template, "역할별") || isKeyMatch(template, "ROLE_GUIDE") || isKeyMatch(template, "role_guide") || isKeyMatch(template, "가이드")) {
            addRoleGuideIntroBlocks(blocks, members);
        } else if (isKeyMatch(template, "DB") || isKeyMatch(template, "db")) {
            addDbPageIntroBlocks(blocks);
        }
    }

    private boolean isKeyMatch(AiResponseDto.TemplateDto template, String keyword) {
        return (template.key() != null && template.key().toLowerCase().contains(keyword.toLowerCase()))
                || (template.title() != null && template.title().contains(keyword));
    }

    /**
     * 페이지 키/제목에 따라 적절한 이모지 아이콘을 반환합니다.
     */
    private String resolvePageEmoji(AiResponseDto.TemplateDto template) {
        String key   = template.key()   != null ? template.key().toLowerCase()   : "";
        String title = template.title() != null ? template.title().toLowerCase() : "";

        // 홈
        if (key.contains("home") || key.contains("홈") || title.contains("home") || title.contains("홈")) return "🏠";
        // 기획
        if (key.contains("plan") || key.contains("기획")) return "💬";
        // 개발
        if (key.contains("dev") || key.contains("개발")) return "💻";
        // DB
        if (key.contains("db") || title.contains("db") || title.contains("데이터")) return "🗄️";
        // 그라운드룰
        if (key.contains("ground") || key.contains("rule") || title.contains("그라운드")) return "📋";
        // 역할별 가이드
        if (key.contains("role") || key.contains("guide") || title.contains("역할") || title.contains("가이드")) return "😀";
        // 기본
        return "📄";
    }

    /**
     * PROJECT_HOME / home / 홈 등 홈 페이지 여부 판별.
     * parentKey == null인 템플릿 중 key 또는 title에 "home", "HOME", "홈" 이 포함된 경우를 홈으로 본다.
     */
    private boolean isRootHomePage(AiResponseDto.TemplateDto template) {
        String key   = template.key()   != null ? template.key().toLowerCase()   : "";
        String title = template.title() != null ? template.title().toLowerCase() : "";
        return key.contains("home") || key.contains("홈") || key.contains("project_home")
                || title.contains("home") || title.contains("홈") || title.contains("project home");
    }

    private boolean isStructuralPage(AiResponseDto.TemplateDto template) {
        return template.parentKey() == null
                || isKeyMatch(template, "기획")
                || isKeyMatch(template, "개발")
                || isKeyMatch(template, "역할별")
                || isKeyMatch(template, "ROLE_GUIDE")
                || isKeyMatch(template, "role_guide")
                || isKeyMatch(template, "가이드")
                || isKeyMatch(template, "DB")
                || isKeyMatch(template, "db");
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

    private void addDbPageIntroBlocks(List<Map<String, Object>> blocks) {
        blocks.add(createCalloutBlock("테이블 명세 및 ERD 관계 정의 데이터베이스는 아래에 자동 생성됩니다.", "🗄️", "gray_background"));
    }

    private void addRoleGuideIntroBlocks(List<Map<String, Object>> blocks, List<MemberDto.InfoResponse> members) {
        blocks.add(createCalloutBlock("각 역할별 업무 가이드입니다.", "😀", "gray_background"));
        blocks.add(createDividerBlock());

        Map<String, String> roleGuideMap = Map.of(
                "BACKEND",   "• API 설계 및 개발\n• 데이터베이스 설계 및 관리\n• 서버 배포 및 운영\n• 프론트엔드와 API 명세 공유",
                "백엔드",     "• API 설계 및 개발\n• 데이터베이스 설계 및 관리\n• 서버 배포 및 운영\n• 프론트엔드와 API 명세 공유",
                "FRONTEND",  "• UI/UX 구현\n• 백엔드 API 연동\n• 컴포넌트 설계 및 재사용성 관리\n• 반응형 디자인 적용",
                "프론트엔드", "• UI/UX 구현\n• 백엔드 API 연동\n• 컴포넌트 설계 및 재사용성 관리\n• 반응형 디자인 적용",
                "AI",        "• AI 모델 설계 및 학습\n• 데이터 전처리 및 분석\n• 모델 성능 평가 및 개선\n• 백엔드와 AI API 연동",
                "LEADER",    "• 프로젝트 전체 일정 관리\n• 팀원 역할 분배 및 조율\n• 회의 진행 및 의사결정\n• 최종 산출물 검토"
        );

        Set<String> addedRoles = new HashSet<>();
        if (members != null) {
            for (MemberDto.InfoResponse member : members) {
                String role = member.getRole();
                if (role == null || addedRoles.contains(role.toUpperCase())) continue;
                addedRoles.add(role.toUpperCase());

                String guideContent = roleGuideMap.entrySet().stream()
                        .filter(e -> role.toUpperCase().contains(e.getKey().toUpperCase())
                                || e.getKey().toUpperCase().contains(role.toUpperCase()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse("• 역할에 맞는 업무를 수행합니다.\n• 팀원과 적극적으로 소통합니다.");

                blocks.add(createHeadingBlock(role + " 가이드"));
                blocks.add(createParagraphBlock(guideContent));
                blocks.add(createDividerBlock());
            }
        }

        // 팀원 정보가 없으면 기본 가이드 제공
        if (addedRoles.isEmpty()) {
            for (Map.Entry<String, String> entry : roleGuideMap.entrySet()) {
                if (List.of("BACKEND", "FRONTEND", "AI", "LEADER").contains(entry.getKey())) {
                    blocks.add(createHeadingBlock(entry.getKey() + " 가이드"));
                    blocks.add(createParagraphBlock(entry.getValue()));
                    blocks.add(createDividerBlock());
                }
            }
        }
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
