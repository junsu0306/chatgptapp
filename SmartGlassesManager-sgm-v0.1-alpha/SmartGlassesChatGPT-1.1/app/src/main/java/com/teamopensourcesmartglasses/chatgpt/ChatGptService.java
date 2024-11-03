package com.teamopensourcesmartglasses.chatgpt;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import com.teamopensmartglasses.sgmlib.DataStreamType;
import com.teamopensmartglasses.sgmlib.FocusStates;
import com.teamopensmartglasses.sgmlib.SGMCommand;
import com.teamopensmartglasses.sgmlib.SGMLib;
import com.teamopensmartglasses.sgmlib.SmartGlassesAndroidService;
import com.teamopensourcesmartglasses.chatgpt.events.ChatReceivedEvent;
import com.teamopensourcesmartglasses.chatgpt.events.QuestionAnswerReceivedEvent;
import com.teamopensourcesmartglasses.chatgpt.events.ChatErrorEvent;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatGptService extends SmartGlassesAndroidService {
    public final String TAG = "SmartGlassesChatGpt_ChatGptService";
    static final String appName = "SmartGlassesChatGpt";

    // Our instance of the SGM library and TTS
    public SGMLib sgmLib;
    public FocusStates focusState;
    public ChatGptBackend chatGptBackend;
    private TextToSpeech tts;

    public StringBuffer messageBuffer = new StringBuffer();
    private boolean userTurnLabelSet = false;
    private boolean chatGptLabelSet = false;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private Future<?> future;
    private boolean openAiKeyProvided = false;
    private ChatGptAppMode mode = ChatGptAppMode.Inactive;
    private boolean useAutoSend;
    private ArrayList<String> commandWords;
    private String scrollingTextTitle = "";
    private final int messageDisplayDurationMs = 3000;

    public ChatGptService() {
        super(MainActivity.class,
                "chatgpt_app",
                1011,
                appName,
                "ChatGPT for smart glasses", com.google.android.material.R.drawable.notify_panel_notification_icon_bg);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        focusState = FocusStates.OUT_FOCUS;

        // Initialize SGM library and TTS
        sgmLib = new SGMLib(this);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });

        // Define and register commands
        SGMCommand startChatCommand = new SGMCommand(
                appName, UUID.fromString("c3b5bbfd-4416-4006-8b40-12346ac3abcf"), new String[]{"conversation"},
                "Start a ChatGPT session for your smart glasses!"
        );
        SGMCommand askGptCommand = new SGMCommand(
                appName, UUID.fromString("c367ba2d-4416-8768-8b15-19046ac3a2af"), new String[]{"question"},
                "Ask a one-shot question to ChatGPT based on your existing context"
        );
        SGMCommand clearContextCommand = new SGMCommand(
                appName, UUID.fromString("2b8d1ba0-f114-11ed-a05b-0242ac120003"), new String[]{"clear"},
                "Clear your conversation context"
        );
        SGMCommand recordConversationCommand = new SGMCommand(
                appName, UUID.fromString("ea89a5ac-6cbd-4867-bd86-1ebce9a27cb3"), new String[]{"listen"},
                "Record your conversation so you can ask ChatGPT for questions later on"
        );

        commandWords = new ArrayList<>();
        commandWords.addAll(startChatCommand.getPhrases());
        commandWords.addAll(askGptCommand.getPhrases());
        commandWords.addAll(clearContextCommand.getPhrases());
        commandWords.addAll(recordConversationCommand.getPhrases());

        sgmLib.registerCommand(startChatCommand, this::startChatCommandCallback);
        sgmLib.registerCommand(askGptCommand, this::askGptCommandCallback);
        sgmLib.registerCommand(recordConversationCommand, this::recordConversationCommandCallback);
        sgmLib.registerCommand(clearContextCommand, this::clearConversationContextCommandCallback);
        sgmLib.subscribe(DataStreamType.TRANSCRIPTION_ENGLISH_STREAM, this::processTranscriptionCallback);


        Log.d(TAG, "onCreate: ChatGPT service and TTS started!");
        EventBus.getDefault().register(this);
        chatGptBackend = new ChatGptBackend();

        SharedPreferences sharedPreferences = getSharedPreferences("user.config", Context.MODE_PRIVATE);
        String savedKey = sharedPreferences.getString("openAiKey", "");
        String systemPrompt = sharedPreferences.getString("systemPrompt", "Default system prompt");
        chatGptBackend.initChatGptService(savedKey, systemPrompt);
        useAutoSend = sharedPreferences.getBoolean("autoSendMessages", true);
        openAiKeyProvided = !savedKey.isEmpty();
    }


    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Called");
        EventBus.getDefault().unregister(this);
        sgmLib.deinit();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    public void startChatCommandCallback(String args, long commandTriggeredTime) {
        Log.d(TAG, "startChatCommandCallback: Start ChatGPT command called");
        scrollingTextTitle = "Conversation";
        sgmLib.requestFocus(this::focusChangedCallback);
        mode = ChatGptAppMode.Conversation;
        resetUserMessage();
    }

    public void askGptCommandCallback(String args, long commandTriggeredTime) {
        Log.d(TAG, "askGptCommandCallback: Ask ChatGPT command called");
        scrollingTextTitle = "Question";
        sgmLib.requestFocus(this::focusChangedCallback);
        mode = ChatGptAppMode.Question;
        resetUserMessage();

        // CameraX로 사진 촬영 시작 및 이미지 처리
        startCameraAndTakePicture(this);
    }


    public void startCameraAndTakePicture(Context context) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 후면 카메라 선택
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // ImageCapture만 설정 (Preview 제외)
                ImageCapture imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                // 카메라 제공자에 ImageCapture 객체만 바인딩
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) context, cameraSelector, imageCapture);

                // 사진 촬영
                takePicture(imageCapture);
            } catch (Exception e) {
                Log.e(TAG, "Camera binding failed", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void takePicture(ImageCapture imageCapture) {
        File photoFile = new File(getFilesDir(), "photo.jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Log.d(TAG, "Photo capture succeeded: " + photoFile.getAbsolutePath());
                        String base64Image = encodeImageToBase64(photoFile.getAbsolutePath());
                        if (base64Image != null) {
                            // 촬영된 사진을 GPT로 전송
                            chatGptBackend.sendImageToGpt(base64Image);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                    }
                });
    }


    private String encodeImageToBase64(String imagePath) {
        try (FileInputStream inputStream = new FileInputStream(new File(imagePath))) {
            byte[] imageBytes = new byte[inputStream.available()];
            inputStream.read(imageBytes);
            return Base64.encodeToString(imageBytes, Base64.NO_WRAP);
        } catch (IOException e) {
            Log.e(TAG, "Failed to encode image to Base64", e);
            return null;
        }
    }






    public void processTranscriptionCallback(String transcript, long timestamp, boolean isFinal) {
        if (!focusState.equals(FocusStates.IN_FOCUS) || mode == ChatGptAppMode.Inactive) return;
        if (isFinal && commandWords.contains(transcript)) return;

        if (isFinal && mode == ChatGptAppMode.Record) {
            chatGptBackend.sendChatToMemory(transcript);
            sgmLib.pushScrollingText(transcript);
        } else if (isFinal && openAiKeyProvided) {
            if (useAutoSend) {
                messageBuffer.append(transcript).append(" ");
                if (future != null) future.cancel(false);
                future = executorService.schedule(this::sendMessageToChatGpt, 7, TimeUnit.SECONDS);
            } else if ("send message".equals(transcript)) {
                sendMessageToChatGpt();
            } else {
                messageBuffer.append(transcript).append(" ");
            }
            if (!userTurnLabelSet) {
                sgmLib.pushScrollingText("User: " + transcript);
                userTurnLabelSet = true;
            }
        }
    }

    private void sendMessageToChatGpt() {
        String message = messageBuffer.toString();
        if (!message.isEmpty()) {
            chatGptBackend.sendChatToGpt(message, mode);
            messageBuffer = new StringBuffer();
        }
    }

    private void resetUserMessage() {
        if (future != null) future.cancel(false);
        messageBuffer = new StringBuffer();
    }

    @Subscribe
    public void onChatReceived(ChatReceivedEvent event) {
        // This handles Conversation mode TTS
        ExecutorService printExecutorService = Executors.newSingleThreadExecutor();
        printExecutorService.execute(() -> {
            String[] words = event.getMessage().split("\\s+");
            int wordCount = words.length;
            int groupSize = 23;

            // Push scrolling text in groups for the glasses display
            for (int i = 0; i < wordCount; i += groupSize) {
                if (Thread.currentThread().isInterrupted()) return;
                String groupText = String.join(" ", Arrays.copyOfRange(words, i, Math.min(i + groupSize, wordCount)));
                sgmLib.pushScrollingText(chatGptLabelSet ? groupText.trim() : "ChatGpt: " + groupText.trim());
                chatGptLabelSet = true;
                try {
                    Thread.sleep(messageDisplayDurationMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Use TTS to speak the message
            if (tts != null) {
                tts.speak(event.getMessage(), TextToSpeech.QUEUE_FLUSH, null, "ChatResponse");
            }
            chatGptLabelSet = false;
        });
        userTurnLabelSet = false;
    }




    @Subscribe
    public void onQuestionAnswerReceived(QuestionAnswerReceivedEvent event) {
        String answer = "Image Description:\n" + event.getAnswer();
        sgmLib.sendReferenceCard("Image Analysis Result", answer);
        if (tts != null) tts.speak(event.getAnswer(), TextToSpeech.QUEUE_FLUSH, null, "AnswerResponse");
        mode = ChatGptAppMode.Inactive;
    }


    @Subscribe
    public void onChatError(ChatErrorEvent event) {
        sgmLib.sendReferenceCard("Error", event.getErrorMessage());
    }

    public void focusChangedCallback(FocusStates focusState) {
        this.focusState = focusState;
        sgmLib.stopScrollingText();
        if (focusState.equals(FocusStates.IN_FOCUS)) {
            sgmLib.startScrollingText(scrollingTextTitle);
            messageBuffer = new StringBuffer();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

    }
}






