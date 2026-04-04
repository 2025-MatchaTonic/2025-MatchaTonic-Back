package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.ProjectDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.entity.project.ProjectSessionSummary;
import com.example.MatchaTonic.Back.repository.project.ProjectMemberRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectSessionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSessionSummaryRepository summaryRepository;

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

        if (!project.getLeader().getId().equals(user.getId())) {
            throw new IllegalStateException("프로젝트 삭제 권한이 없습니다.");
        }

        log.info("프로젝트 강제 삭제 시작 - ID: {}", projectId);

        // 1. 요약 정보 삭제
        summaryRepository.findByProject(project).ifPresent(summaryRepository::delete);

        // 2. 채팅 메시지 '벌크' 삭제 (DB에서 직접 제거)
        projectRepository.deleteChatMessagesByProjectId(projectId);

        // 3. 멤버 삭제
        projectMemberRepository.deleteByProject(project);

        // 4.영속성 컨텍스트에 남은 프로젝트 객체를 강제로 비우거나, 자식과의 연결 고리를 메모리에서 끊기
        project.getChatMessages().clear();
        project.getMembers().clear();

        // 5. DB 반영
        projectRepository.flush();

        // 6. 부모 삭제
        projectRepository.delete(project);

        log.info("프로젝트 최종 삭제 성공 - ID: {}", projectId);
    }

    // 상세조회
    @Transactional(readOnly = true)
    public ProjectDto.DetailResponse getProjectDetail(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("해당 프로젝트에 접근 권한이 없습니다."));

        // DB에서 요약 정보를 직접 조회
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


    // 프로젝트 정보 및 요약 업데이트
    public void updateSessionSummary(Long projectId, ProjectDto.SummaryUpdateRequest request, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        projectMemberRepository.findByUserAndProject(user, project)
                .orElseThrow(() -> new IllegalStateException("업데이트 권한이 없습니다."));

        if (request.getName() != null && !request.getName().isEmpty()) {
            project.updateName(request.getName());
        }
        if (request.getSubject() != null) {
            project.updateSubject(request.getSubject());
        }

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

        summaryRepository.save(summary);
        projectRepository.save(project);

        if (request.getGoal() != null && request.getGoal().length() > 5) {
            project.updateStatus("PLANNING_DONE");
        }

        log.info("수동 업데이트 완료 - 프로젝트ID: {}, 업데이트 소스: MANUAL", projectId);
    }
}