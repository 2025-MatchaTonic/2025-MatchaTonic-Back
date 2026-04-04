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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        log.info("프로젝트 삭제 시도 - ID: {}", projectId);

        summaryRepository.findByProject(project).ifPresent(summaryRepository::delete);
        projectRepository.deleteChatMessagesByProjectId(projectId);
        projectMemberRepository.deleteMembersByProjectId(projectId);

        projectRepository.flush();
        projectRepository.delete(project);
        projectRepository.flush();

        log.info("프로젝트 삭제 최종 완료 - ID: {}", projectId);
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

    // 프로젝트 정보 및 세션 요약 수동 업데이트 (AI 분석 결과와 동기화 포함)
    @Transactional
    public void updateSessionSummary(Long projectId, ProjectDto.SummaryUpdateRequest request, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("업데이트 권한이 없습니다."));

        // 1. 프로젝트 기본 정보 업데이트
        if (request.getName() != null && !request.getName().isEmpty()) {
            project.updateName(request.getName());
        }
        if (request.getSubject() != null) {
            project.updateSubject(request.getSubject());
        }

        // 2. 세션 요약 테이블 업데이트
        ProjectSessionSummary summary = summaryRepository.findByProject(project)
                .orElseGet(() -> ProjectSessionSummary.builder()
                        .project(project)
                        .build());

        String titleToUpdate = (request.getTitle() != null) ? request.getTitle() : project.getName();

        summary.updateAll(
                titleToUpdate,
                request.getGoal(),
                request.getTeamSize(),
                request.getRoles(),
                request.getDueDate(),
                request.getDeliverables(),
                user,
                "MANUAL"
        );

        // 3. 수동 수정본을 AI CollectedData에도 반영하여 동기화
        Map<String, Object> aiData = project.getAiCollectedDataMap();
        if (request.getGoal() != null) aiData.put("goal", request.getGoal());
        if (request.getTitle() != null) aiData.put("title", request.getTitle());

        try {
            String updatedAiDataJson = objectMapper.writeValueAsString(aiData);
            project.updateAiContext(project.getAiCurrentStatus(), updatedAiDataJson);
        } catch (Exception e) {
            log.error("AI 데이터 동기화 실패: {}", e.getMessage());
        }

        summaryRepository.save(summary);
        projectRepository.save(project);

        if (request.getGoal() != null && request.getGoal().length() > 5) {
            project.updateStatus("PLANNING_DONE");
        }

        log.info("수동 업데이트 완료 및 AI 컨텍스트 동기화 - 프로젝트ID: {}", projectId);
    }

    // AI 분석 결과 DB 업데이트 로직
    @Transactional
    public void updateSummaryFromAi(Long projectId, AiResponseDto aiResponse) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        if (aiResponse.templates() == null || aiResponse.templates().isEmpty()) return;
        AiResponseDto.TemplateDto template = aiResponse.templates().get(0);

        ProjectSessionSummary summary = summaryRepository.findByProject(project)
                .orElseGet(() -> ProjectSessionSummary.builder()
                        .project(project)
                        .build());

        summary.updateAll(
                template.title() != null ? template.title() : project.getName(),
                template.content() != null ? String.valueOf(template.content()) : "",
                "미지정",
                "AI 분석 역할",
                "기한 미정",
                "AI 생성 결과물",
                project.getLeader(),
                "AI"
        );

        summaryRepository.save(summary);
        log.info("AI 자동 요약 업데이트 완료 - 프로젝트ID: {}, Source: AI", projectId);
    }
}