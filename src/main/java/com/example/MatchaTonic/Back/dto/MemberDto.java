package com.example.MatchaTonic.Back.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinRequest {
        private String inviteCode;
    }

    @Getter
    @Builder
    public static class InfoResponse {
        private String name;
        private String email;
        private String role;
    }
}