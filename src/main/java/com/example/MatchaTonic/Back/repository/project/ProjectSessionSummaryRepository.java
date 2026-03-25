package com.example.MatchaTonic.Back.repository.project;

import com.example.MatchaTonic.Back.entity.project.Project;
import com.example.MatchaTonic.Back.entity.project.ProjectSessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProjectSessionSummaryRepository extends JpaRepository<ProjectSessionSummary, Long> {
    Optional<ProjectSessionSummary> findByProject(Project project);
    Optional<ProjectSessionSummary> findByProjectId(Long projectId);
}