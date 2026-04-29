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

        // 1. 자식 데이터 벌크 삭제 (DB에서 직접 삭제)
        summaryRepository.findByProject(project).ifPresent(summaryRepository::delete);
        projectRepository.deleteChatMessagesByProjectId(projectId);
        projectMemberRepository.deleteMembersByProjectId(projectId);

        // 2. 벌크 삭제 후 1차 캐시를 비우기 -> 이걸 안 하면 project 객체가 삭제된 자식들을 계속 잡고 있어서 Transient 에러가 남
        entityManager.flush();
        entityManager.clear();

        // 3. 깨끗해진 영속성 컨텍스트에서 프로젝트를 다시 조회해서 삭제
        Project freshProject = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("삭제할 프로젝트를 찾을 수 없습니다."));

        projectRepository.delete(freshProject);
        projectRepository.flush();

        log.info("프로젝트 삭제 프로세스 완료 - ID: {}", projectId);
    }

    // [추가됨] 세션 요약 정보 단독 조회
    @Transactional(readOnly = true)
    public ProjectDto.SessionSummaryDto getProjectSummaryOnly(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("해당 프로젝트에 접근 권한이 없습니다."));

        ProjectSessionSummary summary = summaryRepository.findByProject(project).orElse(null);

        if (summary == null) {
            return null;
        }

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

        if (hasText(request.getName())) {
            project.updateName(request.getName().trim());
        }
        if (hasText(request.getSubject())) {
            project.updateSubject(request.getSubject().trim());
        }

        ProjectSessionSummary summary = summaryRepository.findByProject(project)
                .orElseGet(() -> ProjectSessionSummary.builder()
                        .project(project)
                        .build());

        String nextTitle = hasText(request.getTitle())
                ? request.getTitle().trim()
                : firstNonBlank(summary.getTitle(), project.getName());

        String nextSubject = hasText(request.getSubject())
                ? request.getSubject().trim()
                : summary.getSubject();

        String nextGoal = hasText(request.getGoal())
                ? request.getGoal().trim()
                : summary.getGoal();

        String nextTeamSize = hasText(request.getTeamSize())
                ? request.getTeamSize().trim()
                : summary.getTeamSize();

        String nextRoles = hasText(request.getRoles())
                ? request.getRoles().trim()
                : summary.getRoles();

        String nextDueDate = hasText(request.getDueDate())
                ? request.getDueDate().trim()
                : summary.getDueDate();

        String nextDeliverables = hasText(request.getDeliverables())
                ? request.getDeliverables().trim()
                : summary.getDeliverables();

        summary.updateAll(
                nextTitle,
                nextSubject,
                nextGoal,
                nextTeamSize,
                nextRoles,
                nextDueDate,
                nextDeliverables,
                user,
                "MANUAL"
        );

        Map<String, Object> aiData = safeAiCollectedData(project);

        overlayNonBlank(aiData, "subject", request.getSubject());
        overlayNonBlank(aiData, "title", request.getTitle());
        overlayNonBlank(aiData, "goal", request.getGoal());
        overlayNonBlank(aiData, "teamSize", request.getTeamSize());
        overlayNonBlank(aiData, "roles", request.getRoles());
        overlayNonBlank(aiData, "dueDate", request.getDueDate());
        overlayNonBlank(aiData, "deliverables", request.getDeliverables());

        if (!hasText(asString(aiData.get("title")))) {
            aiData.put("title", nextTitle);
        }

        try {
            String updatedAiDataJson = objectMapper.writeValueAsString(aiData);
            String currentStatus = hasText(project.getAiCurrentStatus()) ? project.getAiCurrentStatus() : "EXPLORE";
            project.updateAiContext(currentStatus, updatedAiDataJson);
        } catch (Exception e) {
            log.error("AI 데이터 동기화 실패 - projectId={}: {}", projectId, e.getMessage(), e);
        }

        summaryRepository.save(summary);
        projectRepository.save(project);

        if (hasText(nextGoal) || hasText(project.getSubject())) {
            project.updateStatus("PLANNING_DONE");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildCollectedData(Project project) {
        Map<String, Object> merged = new HashMap<>(safeAiCollectedData(project));

        summaryRepository.findByProject(project).ifPresent(summary -> {
            Map<String, Object> summaryMap = summary.toDataMap();
            if (summaryMap != null) {
                overlayNonBlank(merged, "subject", summaryMap.get("subject"));
                overlayNonBlank(merged, "title", summaryMap.get("title"));
                overlayNonBlank(merged, "goal", summaryMap.get("goal"));
                overlayNonBlank(merged, "teamSize", summaryMap.get("teamSize"));
                overlayNonBlank(merged, "roles", summaryMap.get("roles"));
                overlayNonBlank(merged, "dueDate", summaryMap.get("dueDate"));
                overlayNonBlank(merged, "deliverables", summaryMap.get("deliverables"));
            }
        });

        sanitizeTitle(project, merged);
        return merged;
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
                .orElseGet(() -> ProjectSessionSummary.builder()
                        .project(project)
                        .build());

        String nextTitle = hasText(summary.getTitle())
                ? summary.getTitle()
                : firstNonBlank(template.title(), project.getName());

        String nextSubject = hasText(summary.getSubject())
                ? summary.getSubject()
                : project.getSubject();

        String nextGoal = hasText(summary.getGoal())
                ? summary.getGoal()
                : (template.content() != null ? String.valueOf(template.content()) : null);

        String nextTeamSize = hasText(summary.getTeamSize()) ? summary.getTeamSize() : "미지정";
        String nextRoles = hasText(summary.getRoles()) ? summary.getRoles() : "AI 분석 역할";
        String nextDueDate = hasText(summary.getDueDate()) ? summary.getDueDate() : "기한 미정";
        String nextDeliverables = hasText(summary.getDeliverables()) ? summary.getDeliverables() : "AI 생성 결과물";

        summary.updateAll(
                nextTitle,
                nextSubject,
                nextGoal,
                nextTeamSize,
                nextRoles,
                nextDueDate,
                nextDeliverables,
                project.getLeader(),
                "AI"
        );

        summaryRepository.save(summary);
    }

    private Map<String, Object> safeAiCollectedData(Project project) {
        try {
            Map<String, Object> map = project.getAiCollectedDataMap();
            return map != null ? new HashMap<>(map) : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void sanitizeTitle(Project project, Map<String, Object> collectedData) {
        String currentTitle = asString(collectedData.get("title"));
        if (!hasText(currentTitle)) {
            collectedData.put("title", project.getName());
            return;
        }
        collectedData.put("title", currentTitle.trim());
    }

    private void overlayNonBlank(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String str) {
            if (hasText(str)) target.put(key, str.trim());
            return;
        }
        target.put(key, value);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (hasText(first)) return first.trim();
        if (hasText(second)) return second.trim();
        return null;
    }
}