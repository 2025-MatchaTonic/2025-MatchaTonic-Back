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

    @Operation(summary = "새 프로젝트 생성 (PROJ-01, PROJ-02)")
    @PostMapping
    public ResponseEntity<ProjectDto.CreateResponse> createProject(
            @Parameter(hidden = true) @AuthenticationPrincipal String email, // OAuth2User -> String 변경
            @RequestBody ProjectDto.CreateRequest request) {

        User user = getUserFromEmail(email);
        ProjectDto.CreateResponse response = projectService.createProject(request, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "내 프로젝트 목록 조회 (HOME-01, HOME-02)")
    @GetMapping("/me")
    public ResponseEntity<List<ProjectDto.ListResponse>> getMyProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal String email) { // OAuth2User -> String 변경

        User user = getUserFromEmail(email);
        return ResponseEntity.ok(projectService.getMyProjects(user));
    }

    @Operation(summary = "초대 코드로 프로젝트 참여 (PROJ-04)")
    @PostMapping("/join")
    public ResponseEntity<String> joinProject(
            @Parameter(hidden = true) @AuthenticationPrincipal String email, // OAuth2User -> String 변경
            @RequestBody MemberDto.JoinRequest request) {

        User user = getUserFromEmail(email);
        projectService.joinProject(request.getInviteCode(), user);
        return ResponseEntity.ok("성공적으로 참여되었습니다.");
    }

    @Operation(summary = "팀원 목록 조회 (HOME-04)")
    @GetMapping("/{projectId}/members")
    public ResponseEntity<List<MemberDto.InfoResponse>> getProjectMembers(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectService.getProjectMembers(projectId));
    }

    // 헬퍼 메서드명과 로직을 이메일 기반으로 수정
    private User getUserFromEmail(String email) {
        if (email == null) throw new RuntimeException("인증 정보가 없습니다.");
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다."));
    }

    @Operation(summary = "AI 분석 및 노션으로 내보내기 (EXP-01, 02)")
    @PostMapping("/{projectId}/export")
    public ResponseEntity<String> exportToNotion(
            @PathVariable Long projectId,
            @RequestBody ExportRequestDto exportRequestDto) {

        ExportRequestDto finalDto = exportRequestDto.withProjectId(projectId);

        try {
            aiService.processAndExport(finalDto);
            return ResponseEntity.ok("AI 분석 및 노션 내보내기가 성공적으로 완료되었습니다.");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("내보내기 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
}