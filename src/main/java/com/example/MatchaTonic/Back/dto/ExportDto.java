package com.example.MatchaTonic.Back.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExportDto {
    private String notionToken; // 사용자가 발급받은 통합 토큰
    private String pageUrl; // 내보낼 노션 페이지 url
}