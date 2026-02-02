package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByInviteCode(String inviteCode);
}
