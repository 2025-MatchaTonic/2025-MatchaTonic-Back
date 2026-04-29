package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.AiResponseDto;
import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.ProjectDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.entity.project.ProjectSessionSummary;
import com.example.MatchaTonic.Back.repository.project.ProjectMemberRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectSessionSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSessionSummaryRepository summaryRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    // 프로젝트 생성
    public ProjectDto.CreateResponse createProject(ProjectDto.CreateRequest request, User leader) {
        Project project = Project.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .leader(leader)
                .build();

        Project savedProject = projectRepository.save(project);

        ProjectMember projectMember = ProjectMember.builder()
                .user(leader)
                .project(savedProject)
                .role("LEADER")
                .build();

        projectMemberRepository.save(projectMember);
        log.info("프로젝트 생성 완료 - ID: {}, 방장: {}", savedProject.getId(), leader.getEmail());

        return ProjectDto.CreateResponse.builder()
                .projectId(savedProject.getId())
                .name(savedProject.getName())
                .inviteCode(savedProject.getInviteCode())
                .status(savedProject.getStatus())
                .chatRoomId(savedProject.getId())
                .build();
    }

    // 내 프로젝트 목록 조회
    @Transactional(readOnly = true)
    public List<ProjectDto.ListResponse> getMyProjects(User user) {
        return projectMemberRepository.findByUser(user).stream()
                .map(pm -> ProjectDto.ListResponse.builder()
                        .id(pm.getProject().getId())
                        .name(pm.getProject().getName())
                        .subject(pm.getProject().getSubject())
                        .role(pm.getRole())
                        .status(pm.getProject().getStatus())
                        .chatRoomId(pm.getProject().getId())
                        .inviteCode(pm.getProject().getInviteCode())
                        .createdAt(pm.getProject().getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // 초대 코드로 프로젝트 참여
    public void joinProject(String inviteCode, User user) {
        Project project = projectRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        if (projectMemberRepository.findByUserAndProject(user, project).isPresent()) {
            throw new IllegalStateException("이미 참여 중인 프로젝트입니다.");
        }

        ProjectMember projectMember = ProjectMember.builder()
                .user(user)
                .project(project)
                .role("MEMBER")
                .build();

        projectMemberRepository.save(projectMember);
    }

    // 팀원 목록 조회
    @Transactional(readOnly = true)
    public ProjectDto.TeamResponse getProjectMembers(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        List<MemberDto.InfoResponse> members = projectMemberRepository.findByProject(project).stream()
                .map(m -> MemberDto.InfoResponse.builder()
                        .name(m.getUser().getName())
                        .email(m.getUser().getEmail())
                        .role(m.getRole())
                        .build())
                .collect(Collectors.toList());

        return ProjectDto.TeamResponse.builder()
                .inviteCode(project.getInviteCode())
                .members(members)
                .build();
    }

    // 프로젝트 삭제
    @Transactional
    public void deleteProject(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        if (project.getLeader() == null || !project.getLeader().getId().equals(user.getId())) {
            throw new IllegalStateException("프로젝트 삭제 권한이 없습니다.");
        }

        log.info("프로젝트 삭제 프로세스 시작 - ID: {}", projectId);

        summaryRepository.findByProject(project).ifPresent(summaryRepository::delete);
        projectRepository.deleteChatMessagesByProjectId(projectId);
        projectMemberRepository.deleteMembersByProjectId(projectId);

        entityManager.flush();
        entityManager.clear();

        Project freshProject = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("삭제할 프로젝트를 찾을 수 없습니다."));

        projectRepository.delete(freshProject);
        projectRepository.flush();

        log.info("프로젝트 삭제 프로세스 완료 - ID: {}", projectId);
    }

    // [추가] 세션 요약 정보만 단독 조회 (GET /summary 대응)
    @Transactional(readOnly = true)
    public ProjectDto.SessionSummaryDto getProjectSummaryOnly(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("해당 프로젝트에 접근 권한이 없습니다."));

        ProjectSessionSummary summary = summaryRepository.findByProject(project).orElse(null);

        if (summary == null) return null;

        return ProjectDto.SessionSummaryDto.builder()
                .title(summary.getTitle())
                .subject(summary.getSubject())
                .goal(summary.getGoal())
                .teamSize(summary.getTeamSize())
                .roles(summary.getRoles())
                .dueDate(summary.getDueDate())
                .deliverables(summary.getDeliverables())
                .updatedSource(summary.getUpdatedSource())
                .updatedAt(summary.getUpdatedAt())
                .build();
    }

    // 상세조회
    @Transactional(readOnly = true)
    public ProjectDto.DetailResponse getProjectDetail(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("해당 프로젝트에 접근 권한이 없습니다."));

        ProjectSessionSummary summary = summaryRepository.findByProject(project).orElse(null);
        ProjectDto.SessionSummaryDto summaryDto = null;

        if (summary != null) {
            summaryDto = ProjectDto.SessionSummaryDto.builder()
                    .title(summary.getTitle())
                    .subject(summary.getSubject())
                    .goal(summary.getGoal())
                    .teamSize(summary.getTeamSize())
                    .roles(summary.getRoles())
                    .dueDate(summary.getDueDate())
                    .deliverables(summary.getDeliverables())
                    .updatedSource(summary.getUpdatedSource())
                    .updatedAt(summary.getUpdatedAt())
                    .build();
        }

        return ProjectDto.DetailResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .subject(project.getSubject())
                .inviteCode(project.getInviteCode())
                .status(project.getStatus())
                .chatRoomId(project.getId())
                .summary(summaryDto)
                .build();
    }

    @Transactional
    public void updateSessionSummary(Long projectId, ProjectDto.SummaryUpdateRequest request, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("업데이트 권한이 없습니다."));

        if (hasText(request.getName())) project.updateName(request.getName().trim());
        if (hasText(request.getSubject())) project.updateSubject(request.getSubject().trim());

        ProjectSessionSummary summary = summaryRepository.findByProject(project)
                .orElseGet(() -> ProjectSessionSummary.builder().project(project).build());

        String nextTitle = firstNonBlank(request.getTitle(), summary.getTitle(), project.getName());
        String nextSubject = firstNonBlank(request.getSubject(), summary.getSubject(), project.getSubject());
        String nextGoal = firstNonBlank(request.getGoal(), summary.getGoal());
        String nextTeamSize = firstNonBlank(request.getTeamSize(), summary.getTeamSize());
        String nextRoles = firstNonBlank(request.getRoles(), summary.getRoles());
        String nextDueDate = firstNonBlank(request.getDueDate(), summary.getDueDate());
        String nextDeliverables = firstNonBlank(request.getDeliverables(), summary.getDeliverables());

        summary.updateAll(nextTitle, nextSubject, nextGoal, nextTeamSize, nextRoles, nextDueDate, nextDeliverables, user, "MANUAL");

        syncSummaryToAiContext(project, summary); // AI 컨텍스트 동기화

        summaryRepository.save(summary);
        projectRepository.save(project);

        if (hasText(nextGoal) || hasText(project.getSubject())) {
            project.updateStatus("PLANNING_DONE");
        }
    }

    @Transactional
    public void updateSummaryFromAi(Long projectId, AiResponseDto aiResponse) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        if (aiResponse == null || aiResponse.templates() == null || aiResponse.templates().isEmpty()) {
            return;
        }

        AiResponseDto.TemplateDto template = aiResponse.templates().get(0);
        ProjectSessionSummary summary = summaryRepository.findByProject(project)
                .orElseGet(() -> ProjectSessionSummary.builder().project(project).build());

        // AI가 수집한 전체 데이터(collected_data)를 Map으로 가져오기
        Map<String, Object> aiData = safeAiCollectedData(project);

        // 1. Title: template.title() 최우선 -> aiData -> 기존 summary -> project.name
        String nextTitle = firstNonBlank(template.title(), asString(aiData.get("title")), summary.getTitle(), project.getName());

        // 2. Subject: aiData 최우선 -> 기존 summary -> project.subject
        String nextSubject = firstNonBlank(asString(aiData.get("subject")), summary.getSubject(), project.getSubject());

        // 3. Goal: template.content() 최우선 -> aiData -> 기존 summary
        String nextGoal = firstNonBlank(
                (template.content() != null ? String.valueOf(template.content()) : null),
                asString(aiData.get("goal")),
                summary.getGoal()
        );

        // 4. 나머지 필드들: aiData(collected_data)에서 우선적으로 꺼내기
        String nextTeamSize = firstNonBlank(asString(aiData.get("teamSize")), summary.getTeamSize(), "미지정");
        String nextRoles = firstNonBlank(asString(aiData.get("roles")), summary.getRoles(), "AI 분석 역할");
        String nextDueDate = firstNonBlank(asString(aiData.get("dueDate")), summary.getDueDate(), "기한 미정");
        String nextDeliverables = firstNonBlank(asString(aiData.get("deliverables")), summary.getDeliverables(), "AI 생성 결과물");

        // 엔티티 업데이트
        summary.updateAll(nextTitle, nextSubject, nextGoal, nextTeamSize, nextRoles, nextDueDate, nextDeliverables, project.getLeader(), "AI");

        summaryRepository.save(summary);

        // AI가 업데이트한 내용을 다시 AI 컨텍스트에 동기화
        syncSummaryToAiContext(project, summary);
    }

    private void syncSummaryToAiContext(Project project, ProjectSessionSummary summary) {
        try {
            Map<String, Object> aiData = safeAiCollectedData(project);
            Map<String, Object> summaryMap = summary.toDataMap();
            if (summaryMap != null) {
                aiData.putAll(summaryMap);
            }
            String updatedAiDataJson = objectMapper.writeValueAsString(aiData);
            String currentStatus = hasText(project.getAiCurrentStatus()) ? project.getAiCurrentStatus() : "EXPLORE";
            project.updateAiContext(currentStatus, updatedAiDataJson);
        } catch (Exception e) {
            log.error("AI 컨텍스트 동기화 실패: {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildCollectedData(Project project) {
        Map<String, Object> merged = new HashMap<>(safeAiCollectedData(project));
        summaryRepository.findByProject(project).ifPresent(summary -> {
            Map<String, Object> summaryMap = summary.toDataMap();
            if (summaryMap != null) merged.putAll(summaryMap);
        });
        sanitizeTitle(project, merged);
        return merged;
    }

    private Map<String, Object> safeAiCollectedData(Project project) {
        try {
            Map<String, Object> map = project.getAiCollectedDataMap();
            return map != null ? new HashMap<>(map) : new HashMap<>();
        } catch (Exception e) { return new HashMap<>(); }
    }

    private void sanitizeTitle(Project project, Map<String, Object> collectedData) {
        String currentTitle = asString(collectedData.get("title"));
        collectedData.put("title", hasText(currentTitle) ? currentTitle.trim() : project.getName());
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private String asString(Object value) { return value == null ? null : String.valueOf(value); }

    private String firstNonBlank(String... values) {
        for (String v : values) { if (hasText(v)) return v.trim(); }
        return null;
    }
}