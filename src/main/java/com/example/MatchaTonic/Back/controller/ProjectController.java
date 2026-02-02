package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectMemberRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Project", description = "프로젝트 생성 및 참여 관리 API")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMemberRepository projectMemberRepository;

    @Operation(summary = "새 프로젝트 생성 (PROJ-01)", description = "프로젝트 이름과 주제를 입력하여 새 프로젝트를 생성하고 팀장으로 등록됩니다.")
    @PostMapping
    public ResponseEntity<Object> createProject(
            @Parameter(hidden = true) @AuthenticationPrincipal OAuth2User principal,
            @RequestBody Map<String, String> request) {

        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요합니다.");

        String email = principal.getAttribute("email");
        User user = userRepository.findByEmail(email).orElseThrow();

        Project project = Project.builder()
                .name(request.get("name"))
                .subject(request.get("subject"))
                .leader(user)
                .build();

        Project savedProject = projectRepository.save(project);

        // 생성자(팀장)를 멤버 테이블에도 등록
        projectMemberRepository.save(ProjectMember.builder()
                .user(user)
                .project(savedProject)
                .role("LEADER")
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("inviteCode", savedProject.getInviteCode());
        return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
    }

    @Operation(summary = "초대 코드로 프로젝트 참여 (PROJ-04)", description = "초대 코드를 검증하고 유효할 경우 해당 프로젝트의 팀원으로 등록합니다.")
    @PostMapping("/join")
    public ResponseEntity<Object> joinProject(
            @Parameter(hidden = true) @AuthenticationPrincipal OAuth2User principal,
            @RequestBody Map<String, String> request) {

        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요합니다.");

        // 1. 초대 코드 유효성 검증
        String inviteCode = request.get("inviteCode");
        Project project = projectRepository.findByInviteCode(inviteCode)
                .orElse(null);

        if (project == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("유효하지 않은 초대 코드입니다.");
        }

        // 2. 현재 유저 정보 가져오기
        String email = principal.getAttribute("email");
        User user = userRepository.findByEmail(email).orElseThrow();

        // 3. 중복 참여 확인
        if (projectMemberRepository.findByUserAndProject(user, project).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("이미 참여 중인 프로젝트입니다.");
        }

        // 4. 팀원으로 등록 [PROJ-04]
        projectMemberRepository.save(ProjectMember.builder()
                .user(user)
                .project(project)
                .role("MEMBER")
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("projectId", project.getId());
        response.put("projectName", project.getName());
        response.put("status", "SUCCESS");

        return ResponseEntity.ok((Object) response);
    }
}