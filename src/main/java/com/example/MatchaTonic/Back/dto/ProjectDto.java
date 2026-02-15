package com.example.MatchaTonic.Back.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
        private String name;
        private Long projectId;
        private String inviteCode;
        private String status;
        private Long chatRoomId;
    }
}