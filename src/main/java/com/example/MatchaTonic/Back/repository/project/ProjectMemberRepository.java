package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.entity.login.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    // 특정 유저가 속한 모든 프로젝트 멤버십 조회 (HOME-01)
    List<ProjectMember> findByUser(User user);

    // 특정 프로젝트에 속한 모든 멤버 조회 (HOME-04)
    List<ProjectMember> findByProject(Project project);

    Optional<ProjectMember> findByUserAndProject(User user, Project project);
}