package com.example.MatchaTonic.Back.dto;

import java.util.List;

// 프론트엔드에서 내보내기 버튼을 눌렀을 때 전달되는 데이터
public record ExportRequestDto(
        Long projectId,
        List<String> selectedAnswers // 팀원들이 대화 중 선택한 답변 리스트
) {}