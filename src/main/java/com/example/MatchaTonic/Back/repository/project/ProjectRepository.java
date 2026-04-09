package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByInviteCode(String inviteCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatMessage c WHERE c.project.id = :projectId")
    void deleteChatMessagesByProjectId(@Param("projectId") Long projectId);
}