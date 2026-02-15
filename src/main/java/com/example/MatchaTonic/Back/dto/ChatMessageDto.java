package com.example.MatchaTonic.Back.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDto {

    public enum MessageType {
        ENTER, TALK, SYSTEM
    }

    private MessageType type;
    private Long projectId;
    private String senderEmail;
    private String senderName;
    private String message;
}