package com.example.MatchaTonic.Back.repository;

import com.example.MatchaTonic.Back.entity.manual.Manual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ManualRepository extends JpaRepository<Manual, Long> {

    // 버전(PLAN/DEV)과 카테고리(포함 여부)로 조회
    @Query("SELECT m FROM Manual m WHERE m.target = :target AND m.category LIKE %:category% ORDER BY m.stepOrder ASC")
    List<Manual> findByVersionAndCategory(@Param("target") String target, @Param("category") String category);

    // 전체 카테고리 조회용
    List<Manual> findByTargetOrderByStepOrderAsc(String target);
}