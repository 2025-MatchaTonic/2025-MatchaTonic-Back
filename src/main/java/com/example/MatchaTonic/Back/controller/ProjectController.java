package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.ExportRequestDto;
import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.ProjectDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.service.AiService;
import com.example.MatchaTonic.Back.service.NotionService;
import com.example.MatchaTonic.Back.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Project", description = "프로젝트 관리 API")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final NotionService notionService;
    private final AiService aiService;

    @Operation(summary = "새 프로젝트 생성")
    @PostMapping
    public ResponseEntity<ProjectDto.CreateResponse> createProject(
            @Parameter(hidden = true) @AuthenticationPrincipal String email,
            @RequestBody ProjectDto.CreateRequest request) {

        User user = getUserFromEmail(email);
        ProjectDto.CreateResponse response = projectService.createProject(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "내 프로젝트 목록 조회")
    @GetMapping("/me")
    public ResponseEntity<List<ProjectDto.ListResponse>> getMyProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal String email) {

        User user = getUserFromEmail(email);
        return ResponseEntity.ok(projectService.getMyProjects(user));
    }

    @Operation(summary = "초대 코드로 프로젝트 참여")
    @PostMapping("/join")
    public ResponseEntity<String> joinProject(
            @Parameter(hidden = true) @AuthenticationPrincipal String email,
            @RequestBody MemberDto.JoinRequest request) {

        User user = getUserFromEmail(email);
        projectService.joinProject(request.getInviteCode(), user);
        return ResponseEntity.ok("성공적으로 참여되었습니다.");
    }

    @Operation(summary = "팀원 목록 조회 (inviteCode 포함)")
    @GetMapping("/{projectId}/members")
    public ResponseEntity<ProjectDto.TeamResponse> getProjectMembers(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectService.getProjectMembers(projectId));
    }

    @Operation(summary = "프로젝트 삭제 (방장 전용)")
    @DeleteMapping("/{projectId}")
    public ResponseEntity<String> deleteProject(
            @PathVariable Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal String email) {

        User user = getUserFromEmail(email);
        projectService.deleteProject(projectId, user);
        return ResponseEntity.ok("프로젝트가 삭제되었습니다.");
    }

    // AI 분석 전용 API (프론트엔드가 맨 처음 템플릿 생성 누를 때 호출)
    @Operation(summary = "AI 분석 및 템플릿 생성 (노션 전송 X)")
    @PostMapping("/{projectId}/analyze")
    public ResponseEntity<String> analyzeProject(
            @PathVariable Long projectId,
            @RequestBody ExportRequestDto exportRequestDto) {

        ExportRequestDto finalDto = exportRequestDto.withProjectId(projectId);

        try {
            aiService.processAnalysisOnly(finalDto);
            return ResponseEntity.ok("AI 분석이 성공적으로 완료되었습니다.");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("AI 분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // 노션 내보내기 전용 API (프론트엔드가 노션 URL 입력 후 호출)
    @Operation(summary = "분석 결과를 노션으로 내보내기")
    @PostMapping("/{projectId}/export-to-notion")
    public ResponseEntity<String> exportToNotion(
            @PathVariable Long projectId,
            @RequestBody ExportRequestDto exportRequestDto) {

        // 노션 URL이 비어있는지 사전에 검증하여 500 에러를 방지
        if (exportRequestDto.pageUrl() == null || exportRequestDto.pageUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("노션 페이지 URL을 입력해주세요.");
        }
        if (exportRequestDto.notionToken() == null || exportRequestDto.notionToken().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("노션 통합 토큰을 입력해주세요.");
        }

        ExportRequestDto finalDto = exportRequestDto.withProjectId(projectId);

        try {
            aiService.exportOnly(finalDto);
            return ResponseEntity.ok("노션 내보내기가 성공적으로 완료되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("노션 내보내기 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    @Operation(summary = "프로젝트 상세 조회 (정형화된 세션 요약 포함)")
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectDto.DetailResponse> getProjectDetail(
            @PathVariable Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal String email) {

        User user = getUserFromEmail(email);
        ProjectDto.DetailResponse response = projectService.getProjectDetail(projectId, user);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "프로젝트 세션 요약 정보만 조회")
    @GetMapping("/{projectId}/summary")
    public ResponseEntity<ProjectDto.SessionSummaryDto> getProjectSummary(
            @PathVariable Long projectId,
            @Parameter(hidden = true) @AuthenticationPrincipal String email) {

        User user = getUserFromEmail(email);
        ProjectDto.SessionSummaryDto response = projectService.getProjectSummaryOnly(projectId, user);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "프로젝트 정보 및 세션 요약 수동 업데이트 ")
    @PatchMapping("/{projectId}/summary")
    public ResponseEntity<String> updateSessionSummary(
            @PathVariable Long projectId,
            @RequestBody ProjectDto.SummaryUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal String email) {

        User user = getUserFromEmail(email);
        projectService.updateSessionSummary(projectId, request, user);
        return ResponseEntity.ok("프로젝트 정보 및 세션 요약이 성공적으로 업데이트되었습니다.");
    }

    private User getUserFromEmail(String email) {
        if (email == null || email.isBlank() || "anonymousUser".equals(email)) {
            throw new IllegalStateException("인증 정보가 없습니다.");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("유저를 찾을 수 없습니다."));
    }
}
