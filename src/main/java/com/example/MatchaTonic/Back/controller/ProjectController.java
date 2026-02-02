package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    ///[PROJ-01, PROJ-02] 새 프로젝트 생성
    @PostMapping
    public ResponseEntity<Object> createProject(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody Map<String, String> request) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인이 필요합니다.");
        }

        // 1. 현재 로그인한 유저 찾기
        String email = principal.getAttribute("email");
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        // 2. 프로젝트 생성
        Project project = Project.builder()
                .name(request.get("name"))
                .subject(request.get("subject"))
                .leader(user)
                .build();

        // 3. 저장
        Project savedProject = projectRepository.save(project);

        // 4. 응답 데이터 구성
        Map<String, Object> response = new HashMap<>();
        response.put("projectId", savedProject.getId());
        response.put("name", savedProject.getName());
        response.put("inviteCode", savedProject.getInviteCode());
        response.put("status", savedProject.getStatus());

        return ResponseEntity.status(HttpStatus.CREATED).body((Object) response);
    }
}