package com.teamopensourcesmartglasses.chatgpt.events;


public class ChatReceivedEvent {
    private final String message;

    public ChatReceivedEvent(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
