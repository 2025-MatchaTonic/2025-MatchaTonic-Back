package com.example.MatchaTonic.Back.controller;

import com.example.MatchaTonic.Back.dto.UserResponseDto;
import com.example.MatchaTonic.Back.repository.login.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "사용자 정보 및 인증 관리 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @Operation(summary = "로그인 유저 정보 조회 (SYS-01)", description = "JWT 토큰을 통해 현재 로그인된 사용자의 상세 정보를 반환합니다.")
    @GetMapping("/me")
    public ResponseEntity<Object> getCurrentUser(@Parameter(hidden = true) @AuthenticationPrincipal String email) {

        // 1. JWT 필터에서 이메일을 추출하지 못한 경우 (인증 실패)
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증 정보가 없습니다. 다시 로그인해주세요.");
        }

        // 2. DB 조회 및 결과 반환
        return userRepository.findByEmail(email)
                .map(user -> {
                    UserResponseDto response = UserResponseDto.builder()
                            .id(user.getId())
                            .email(user.getEmail())
                            .name(user.getName())
                            .role(user.getRole().name())
                            .picture(null)
                            .build();
                    return ResponseEntity.ok((Object) response);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("사용자를 찾을 수 없습니다."));
    }
}