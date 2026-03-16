package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.ProjectDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.repository.project.ProjectMemberRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
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

    /**
     * 프로젝트 생성
     */
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

    /**
     * 내 프로젝트 목록 조회 (inviteCode 포함)
     */
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
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 초대코드로 프로젝트 참여
     */
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

    /**
     * 팀원 목록 조회
     */
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

    /**
     * 프로젝트 삭제
     */
    public void deleteProject(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        if (!project.getLeader().getId().equals(user.getId())) {
            throw new IllegalStateException("프로젝트 삭제 권한이 없습니다.");
        }

        projectRepository.delete(project);
    }
}