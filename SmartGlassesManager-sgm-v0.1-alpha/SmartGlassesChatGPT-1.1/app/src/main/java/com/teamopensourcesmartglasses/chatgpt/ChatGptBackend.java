package com.teamopensourcesmartglasses.chatgpt;

import android.util.Log;

import androidx.annotation.NonNull;

import com.teamopensourcesmartglasses.chatgpt.events.ChatErrorEvent;
import com.teamopensourcesmartglasses.chatgpt.events.ChatReceivedEvent;
import com.teamopensourcesmartglasses.chatgpt.events.QuestionAnswerReceivedEvent;
import com.teamopensourcesmartglasses.chatgpt.utils.MessageStore;
import com.theokanning.openai.completion.chat.ChatCompletionChoice;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import org.greenrobot.eventbus.EventBus;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public class ChatGptBackend {
    private final String TAG = "ChatGptBackend";
    private OpenAiService service;
    private final MessageStore messages;
    private final int chatGptMaxTokenSize = 4096;
    private final int messageDefaultWordsChunkSize = 100;
    private final int openAiServiceTimeoutDuration = 110;
    private StringBuffer recordingBuffer = new StringBuffer();
    private int getWordCount(String message) {
        String[] words = message.split("\\s+");
        return words.length;
    }
    public ChatGptBackend() {
        messages = new MessageStore(chatGptMaxTokenSize);
    }

    public void initChatGptService(String token, String systemMessage) {
        service = new OpenAiService(token, Duration.ofSeconds(openAiServiceTimeoutDuration));
        messages.setSystemMessage(systemMessage);
    }


    public void sendChatToGpt(String message, ChatGptAppMode mode) {
        if (service == null) {
            EventBus.getDefault().post(new ChatErrorEvent("OpenAI Key not set."));
            return;
        }
        messages.addMessage(ChatMessageRole.USER.value(), message);
        processChatCompletion(message, mode);
    }

    public void sendImageToGpt(String base64Image) {
        if (service == null) {
            EventBus.getDefault().post(new ChatErrorEvent("OpenAI Key not set."));
            return;
        }
        String imagePrompt = "Describe this image in simple terms for visually impaired users.";
        ChatMessage imageMessage = new ChatMessage("user", imagePrompt);
        ChatMessage imagePayload = new ChatMessage("user", "{ \"type\": \"image_url\", \"image_url\": { \"url\": \"data:image/jpeg;base64," + base64Image + "\", \"detail\": \"high\" } }");

        messages.addMessage(ChatMessageRole.USER.value(), imagePrompt);
        messages.addMessage(ChatMessageRole.USER.value(), imagePayload.getContent());

        // 응답을 QuestionAnswerReceivedEvent로 전달하여 `onQuestionAnswerReceived`에서 처리
        processChatCompletion(imagePrompt, ChatGptAppMode.Question);
    }


    private void processChatCompletion(String message, ChatGptAppMode mode) {
        new Thread(() -> {
            try {
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .model("gpt-4-turbo")
                        .messages(messages.getAllMessages())
                        .maxTokens(4096)
                        .build();
                ChatCompletionResult result = service.createChatCompletion(request);
                String responseText = result.getChoices().get(0).getMessage().getContent();

                if (mode == ChatGptAppMode.Conversation) {
                    EventBus.getDefault().post(new ChatReceivedEvent(responseText));
                } else if (mode == ChatGptAppMode.Question) {
                    EventBus.getDefault().post(new QuestionAnswerReceivedEvent(message, responseText));
                }
            } catch (Exception e) {
                EventBus.getDefault().post(new ChatErrorEvent("Error processing request: " + e.getMessage()));
                Log.e(TAG, "Error: ", e);
            }
        }).start();
    }

    public void sendChatToMemory(String message) {
        // Add to messages here if it is just to record
        // It should be chunked into a decent block size
        Log.d(TAG, "sendChat: In record mode");
        recordingBuffer.append(message);
        recordingBuffer.append(" ");
        Log.d(TAG, "sendChatToMemory: " + recordingBuffer);

        if (getWordCount(recordingBuffer.toString()) > messageDefaultWordsChunkSize) {
            Log.d(TAG, "sendChatToMemory: size is big enough to be a chunk");
            messages.addMessage(ChatMessageRole.USER.value(), recordingBuffer.toString());
            recordingBuffer = new StringBuffer();
        }
    }}




