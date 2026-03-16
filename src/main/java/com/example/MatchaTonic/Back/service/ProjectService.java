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
     * [PROJ-01, 02] 프로젝트 생성
     * 생성한 유저를 명확하게 LEADER로 등록합니다.
     */
    public ProjectDto.CreateResponse createProject(ProjectDto.CreateRequest request, User leader) {
        // 1. 프로젝트 엔티티 생성 및 저장
        Project project = Project.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .leader(leader) // 엔티티 자체의 leader 필드 설정
                .build();

        Project savedProject = projectRepository.save(project);

        // 2. 프로젝트 멤버 테이블에 방장(LEADER)으로 명시적 저장
        ProjectMember projectMember = ProjectMember.builder()
                .user(leader)
                .project(savedProject)
                .role("LEADER") // 권한 확실히 부여
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
     * [HOME-01] 내 프로젝트 목록 조회
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
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * [PROJ-04] 초대 코드로 가입
     */
    public void joinProject(String inviteCode, User user) {
        // 1. 초대 코드로 프로젝트 존재 여부 확인
        Project project = projectRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다."));

        // 2. 이미 가입된 유저인지 확인 (500 에러 방지용)
        if (projectMemberRepository.findByUserAndProject(user, project).isPresent()) {
            log.warn("이미 가입된 프로젝트 가입 시도: ProjectID {}, User {}", project.getId(), user.getEmail());
            throw new IllegalStateException("이미 참여 중인 프로젝트입니다.");
        }

        // 3. 일반 멤버(MEMBER)로 저장
        ProjectMember projectMember = ProjectMember.builder()
                .user(user)
                .project(project)
                .role("MEMBER")
                .build();

        projectMemberRepository.save(projectMember);
        log.info("프로젝트 가입 완료 - ID: {}, 유저: {}", project.getId(), user.getEmail());
    }

    /**
     * [HOME-04] 팀원 목록 조회
     */
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

    /**
     * [DEL-01] 프로젝트 삭제 (방장 전용)
     */
    public void deleteProject(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다."));

        // 방장(Leader) 권한 체크
        if (!project.getLeader().getId().equals(user.getId())) {
            throw new IllegalStateException("프로젝트 삭제 권한이 없습니다. 방장만 삭제할 수 있습니다.");
        }

        projectRepository.delete(project);
        log.info("프로젝트 삭제 완료 - ID: {}", projectId);
    }
}