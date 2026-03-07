package com.example.MatchaTonic.Back.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ErrorResponse {
    private int status;
    private String code;
    private String message;
}