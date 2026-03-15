package com.example.MatchaTonic.Back.repository;

import com.example.MatchaTonic.Back.entity.manual.Manual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManualRepository extends JpaRepository<Manual, Long> {

    // target만으로 전체 조회
    List<Manual> findByTargetOrderByStepOrderAsc(String target);

    // target + category 포함 검색
    List<Manual> findByTargetAndCategoryContainingOrderByStepOrderAsc(String target, String category);
}