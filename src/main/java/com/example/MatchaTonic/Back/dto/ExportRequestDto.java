package com.example.MatchaTonic.Back.dto;

import java.util.List;

// 프론트엔드에서 내보내기 버튼을 눌렀을 때 전달되는 데이터
public record ExportRequestDto(
        Long projectId,
        String templateType,       // "plan" 또는 "dev"
        String content,            // 사용자 추가 요청 사항
        List<String> selectedAnswers, // 팀원들이 대화 중 선택한 답변 리스트

        // 사용자가 팝업창에서 직접 입력한 정보
        String notionToken,        // 노션 통합 토큰 (secret_...)
        String pageUrl             // 노션 부모 페이지 주소 (URL)
) {

    public ExportRequestDto withProjectId(Long projectId) {
        return new ExportRequestDto(
                projectId,
                this.templateType,
                this.content,
                this.selectedAnswers,
                this.notionToken,
                this.pageUrl
        );
    }
}