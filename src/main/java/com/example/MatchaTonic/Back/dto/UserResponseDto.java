package com.example.MatchaTonic.Back.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserResponseDto {
    private Long id;
    private String email;
    private String name;
    private String picture;
    private String role;
}