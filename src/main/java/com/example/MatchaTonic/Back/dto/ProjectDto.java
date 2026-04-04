package com.example.MatchaTonic.Back.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class ProjectDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        private String name;
        private String subject;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateResponse {
        private Long projectId;
        private String name;
        private String inviteCode;
        private String status;
        private Long chatRoomId;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListResponse {
        private Long id;
        private String name;
        private String subject;
        private String role;
        private String status;
        private Long chatRoomId;
        private String inviteCode;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TeamResponse {
        private String inviteCode;
        private List<MemberDto.InfoResponse> members;
    }

    /**
     * 프로젝트 상세 조회 응답
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetailResponse {
        private Long id;
        private String name;
        private String subject;
        private String inviteCode;
        private String status;
        private Long chatRoomId;
        private SessionSummaryDto summary;
    }

    /**
     * 정형화된 세션 요약 정보를 위한 내부 DTO
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionSummaryDto {
        private String title;
        private String goal;
        private String teamSize;
        private String roles;
        private String dueDate;
        private String deliverables;
        private String updatedSource;
        private LocalDateTime updatedAt;
    }

    /**
     * 수동으로 프로젝트 정보(이름 포함) 및 요약을 수정할 때 사용하는 요청 DTO
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryUpdateRequest {

        private String name;
        private String subject;

        private String title;
        private String goal;
        private String teamSize;
        private String roles;
        private String dueDate;
        private String deliverables;
    }
}