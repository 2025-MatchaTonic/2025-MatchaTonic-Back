package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectMember;
import com.example.MatchaTonic.Back.entity.login.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    List<ProjectMember> findByUser(User user);

    List<ProjectMember> findByProject(Project project);

    Optional<ProjectMember> findByUserAndProject(User user, Project project);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProjectMember pm WHERE pm.project.id = :projectId")
    void deleteMembersByProjectId(@Param("projectId") Long projectId);
}