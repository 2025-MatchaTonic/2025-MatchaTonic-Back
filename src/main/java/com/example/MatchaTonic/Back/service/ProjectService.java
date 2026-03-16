package com.example.MatchaTonic.Back.service;

import com.example.MatchaTonic.Back.dto.MemberDto;
import com.example.MatchaTonic.Back.dto.ProjectDto;
import com.example.MatchaTonic.Back.entity.login.User;
import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.repository.project.ProjectMemberRepository;
import com.example.MatchaTonic.Back.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectDto.CreateResponse createProject(ProjectDto.CreateRequest request, User leader) {
        Project project = Project.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .leader(leader)
                .build();

        Project savedProject = projectRepository.save(project);

        projectMemberRepository.save(ProjectMember.builder()
                .user(leader)
                .project(savedProject)
                .role("LEADER")
                .build());

        return ProjectDto.CreateResponse.builder()
                .projectId(savedProject.getId())
                .name(savedProject.getName())
                .inviteCode(savedProject.getInviteCode())
                .status(savedProject.getStatus())
                .chatRoomId(savedProject.getId())
                .build();
    }

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
                        .build())
                .collect(Collectors.toList());
    }

    public void joinProject(String inviteCode, User user) {
        Project project = projectRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        if (projectMemberRepository.findByUserAndProject(user, project).isPresent()) {
            throw new IllegalStateException("이미 참여 중인 프로젝트입니다.");
        }

        projectMemberRepository.save(ProjectMember.builder()
                .user(user)
                .project(project)
                .role("MEMBER")
                .build());
    }

    @Transactional(readOnly = true)
    public List<MemberDto.InfoResponse> getProjectMembers(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        return projectMemberRepository.findByProject(project).stream()
                .map(m -> MemberDto.InfoResponse.builder()
                        .name(m.getUser().getName())
                        .email(m.getUser().getEmail())
                        .role(m.getRole())
                        .build())
                .collect(Collectors.toList());
    }

    // 프로젝트 삭제 로직 추가
    public void deleteProject(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        // 방장만 삭제 가능하도록 검증
        if (!project.getLeader().getId().equals(user.getId())) {
            throw new IllegalStateException("프로젝트 삭제 권한이 없습니다. 방장만 삭제할 수 있습니다.");
        }

        projectRepository.delete(project);
    }
}