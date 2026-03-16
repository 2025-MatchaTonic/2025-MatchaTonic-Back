package com.example.MatchaTonic.Back.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    public static class CreateResponse {
        private Long projectId;
        private String name;
        private String inviteCode;
        private String status;
        private Long chatRoomId;
    }

    @Getter
    @Builder
    public static class ListResponse {
        private Long id;
        private String name;
        private String subject;
        private String role;
        private String status;
        private Long chatRoomId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TeamResponse {
        private String inviteCode;
        private List<MemberDto.InfoResponse> members;
    }
}