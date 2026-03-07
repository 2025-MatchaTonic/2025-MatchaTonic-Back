package com.example.MatchaTonic.Back.exception;

import com.example.MatchaTonic.Back.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice // 모든 컨트롤러의 예외를 감시합니다
public class GlobalExceptionHandler {

    // 우리가 AiService에서 던졌던 RuntimeException을 처리합니다.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException e) {
        log.error("비즈니스 로직 에러 발생: {}", e.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("BIZ_ERROR")
                .message(e.getMessage()) // "AI 분석 서버와 통신할 수 없습니다" 등이 전달됨
                .build();

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // 그 외 예상치 못한 모든 에러 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAllException(Exception e) {
        log.error("예상치 못한 서버 에러: ", e);

        ErrorResponse response = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("SERVER_ERROR")
                .message("서버 내부 오류가 발생했습니다. 관리자에게 문의하세요.")
                .build();

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}