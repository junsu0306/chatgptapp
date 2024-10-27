package com.smartglassesmanager.androidsmartphone.smartglassescommunicators;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.util.Log;

import com.smartglassesmanager.androidsmartphone.comms.AspWebsocketServer;
import com.smartglassesmanager.androidsmartphone.comms.AudioSystem;
import com.smartglassesmanager.androidsmartphone.comms.MessageTypes;
import com.smartglassesmanager.androidsmartphone.utils.NetworkUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.subjects.PublishSubject;

public class AndroidSGC extends SmartGlassesCommunicator {
    private static final String TAG = "WearableAi_AndroidSGC";

    PublishSubject<JSONObject> dataObservable;

    private static boolean killme;

    private static Handler heart_beat_handler;

    //handler for advertising
    private Handler adv_handler;

    //network details
    public int PORT_NUM = 8891;
    public DatagramSocket adv_socket;
    public String adv_key = "WearableAiCyborg";

    //SOCKET STUFF
    //socket
    public AspWebsocketServer asgWebSocket;

    //acutal socket
    ServerSocket serverSocket;
    Socket socket;
    boolean shouldDie = false;

    //socket threads
    Thread SocketThread = null;
    Thread ReceiveThread = null;
    Thread SendThread = null;
    //queue of data to send through the socket
    public BlockingQueue<byte []> queue;
    //address info
    public  final int SERVER_PORT = 4567;
    //i/o
    public DataOutputStream output;
    public DataInputStream input;
    public  int outbound_heart_beats = 0;

    //other
    final byte [] ack_id = {0x13, 0x37};
    final byte [] heart_beat_id = {0x19, 0x20};
    final byte [] img_id = {0x01, 0x10}; //id for images

    //audio streaming system
    AudioSystem audioSystem;

    Context context;

    public AndroidSGC(Context context, PublishSubject<JSONObject> dataObservable){
        super();
        this.dataObservable = dataObservable;
        this.context = context;

        //create a new queue to hold outbound message
        queue = new ArrayBlockingQueue<byte[]>(50);

        killme = false;

        //state information
        mConnectState = 0;
    }

    //not used/valid yet
    @Override
    protected void setFontSizes(){
        LARGE_FONT = 3;
        MEDIUM_FONT = 2;
        SMALL_FONT = 0;
    }

    public void connectToSmartGlasses(){
        //open the UDP socket to broadcast our IP address
        openSocket();

        //send broadcast over UDP that tells smart glasses they can find us
        adv_handler = new Handler();
        final int delay = 1000; // 1000 milliseconds == 1 second
        adv_handler.postDelayed(new Runnable() {
            public void run() {
                new Thread(new SendAdvThread()).start();
                adv_handler.postDelayed(this, delay);
            }
        }, 5);

        startAsgWebSocketConnection();

        audioSystem = new AudioSystem(context, dataObservable);

        //start first socketThread
        Log.d(TAG, "running start socket");
        startSocket();
    }

    class SendAdvThread extends Thread {
        public void run() {
            //send broadcast so smart glasses know our address
            NetworkUtils.sendBroadcast(adv_key, adv_socket, PORT_NUM, context);
        }
    }

    public void blankScreen(){
//        try{
//            //build json object to send command result
//            JSONObject commandResponseObject = new JSONObject();
//            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.ACTION_SWITCH_MODES);
//            commandResponseObject.put(MessageTypes.NEW_MODE, MessageTypes.MODE_BLANK);
//
//            //send the command result to web socket, to send to asg
//            dataObservable.onNext(commandResponseObject);
//        } catch (JSONException e){
//            e.printStackTrace();
//        }
    }

    public void startAsgWebSocketConnection(){
        Log.d(TAG, "Starting WebSocket Server");
        //String address = "localhost:8887";
        //InetSocketAddress inetSockAddress = new InetSocketAddress(address);
        int port = 8887;
        asgWebSocket = new AspWebsocketServer(port);
        asgWebSocket.setObservable(dataObservable);
        asgWebSocket.start();
        Log.d(TAG, "WebSocket Server STARTED");
    }

    //SOCKET STUFF
    public void startSocket(){
        //start first socketThread
        Log.d(TAG, "socket val in startSocket: " + socket);
        if (socket == null) {
            Log.d(TAG, "starting new SocketThread" + socket);
            connectionEvent(1);
            SocketThread = new Thread(new SocketThread());
            SocketThread.start();

            //setup handler to handle keeping connection alive, all subsequent start of SocketThread
            //start a new handler thread to send heartbeats
            HandlerThread thread = new HandlerThread("HeartBeater");
            thread.start();
            heart_beat_handler = new Handler(thread.getLooper());
            final int hb_delay = 3000;
            final int min_hb_delay = 1000;
            final int max_hb_delay = 2000;
            Random rand = new Random();
            heart_beat_handler.postDelayed(new Runnable() {
                public void run() {
                    heartBeat();
                    //random hb_delay for heart beat so as to disallow synchronized failure between client and server
                    int random_hb_delay = rand.nextInt((max_hb_delay - min_hb_delay) + 1) + min_hb_delay;
                    heart_beat_handler.postDelayed(this, random_hb_delay);
                }
            }, hb_delay);

        } else {
            Log.d(TAG, "socket wasn't null, so not starting");
        }

    }

    public void openSocket() {
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        try {
            //Open a random port to send the package
            adv_socket = new DatagramSocket();
            adv_socket.setBroadcast(true);
        } catch (IOException e) {
            Log.e(TAG, "IOException: " + e.getMessage());
        }
    }

    class SocketThread implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "I have started SOCKETTHREAD");
            try {
                if (killme){
                    Log.d(TAG, "I have killed myself");
                    return;
                }
                Log.d(TAG, "Starting new socket, waiting for connection...");
                serverSocket = new ServerSocket(SERVER_PORT);
                //serverSocket.setSoTimeout(2000);
                try {
                    socket = serverSocket.accept();
                    if (killme){
                        return;
                    }
                    socket.setSoTimeout(5000);
                    Log.d(TAG, "Got socket connection.");
                    //output = new PrintWriter(socket.getOutputStream(), true);
                    output = new DataOutputStream(socket.getOutputStream());
                    input = new DataInputStream(new DataInputStream(socket.getInputStream()));
                    connectionEvent(2);
                    if (ReceiveThread == null) { //if the thread is null, make a new one (the first one)
                        ReceiveThread = new Thread(new ReceiveThread());
                        ReceiveThread.start();
                    } else if (!ReceiveThread.isAlive()) { //if the thread is not null but it's dead, let it join then start a new one
                        try {
                            ReceiveThread.join(); //make sure socket thread has joined before throwing off a new one
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        ReceiveThread = new Thread(new ReceiveThread());
                        ReceiveThread.start();
                    }
                    if (SendThread == null) { //if the thread is null, make a new one (the first one)
                        SendThread = new Thread(new SendThread());
                        SendThread.start();
                    } else if (!SendThread.isAlive()) { //if the thread is not null but it's dead, let it join then start a new one
                        try {
                            SendThread.join(); //make sure socket thread has joined before throwing off a new one
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        SendThread =  new Thread(new SendThread());
                        SendThread.start();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    connectionEvent(0);
                }
            } catch (IOException e) {
                e.printStackTrace();
                connectionEvent(0);
            }
        }
    }

    //receives messages
    public void heartBeat(){
        //check if we are still connected.
        //if not , reconnect,
        //if we are connected, send a heart beat to make sure we are still connected
        if (mConnectState == 0 && !killme) {
            restartSocket();
        } else if (mConnectState == 2){
            //make sure we don't have a ton of outbound heart beats unresponded to
            if (outbound_heart_beats > 5) {
                restartSocket();
                return;
            }

            //increment counter
            outbound_heart_beats++;

            //send heart beat
            sendBytes(heart_beat_id, null);
        }
    }

    //receives messages
    public class ReceiveThread implements Runnable {
        @Override
        public void run() {
            //System.out.println("Receive Started, mconnect: " + mConnectState);
            while (true) {
                if (killme){
                    return;
                }
                if (mConnectState != 2){
                    break;
                }
                byte b1, b2;
                byte [] raw_data = null;
                byte goodbye1, goodbye2, goodbye3;
                try {
                    byte hello1 = input.readByte(); // read hello of incoming message
                    byte hello2 = input.readByte(); // read hello of incoming message
                    byte hello3 = input.readByte(); // read hello of incoming message

                    //make sure header is verified
                    if (hello1 != 0x01 || hello2 != 0x02 || hello3 != 0x03){
                        break;
                    }
                    //length of body
                    int body_len = input.readInt();

                    //read in message id bytes
                    b1 = input.readByte();
                    b2 = input.readByte();

                    //read in message body (if there is one)
                    if (body_len > 0){
                        raw_data = new byte[body_len];
                        input.readFully(raw_data, 0, body_len); // read the body
                    }
                    goodbye1 = input.readByte(); // read goodbye of incoming message
                    goodbye2 = input.readByte(); // read goodbye of incoming message
                    goodbye3 = input.readByte(); // read goodbye of incoming message
                } catch (IOException e) {
                    if (killme){
                        Log.d(TAG, "Socket closed (by us), cleaning up.");
                    } else {
                        Log.d(TAG, "Socket closed.");
                        e.printStackTrace();
                    }
                    break;
                }

                //make sure footer is verified
                if (goodbye1 != 0x03 || goodbye2 != 0x02 || goodbye3 != 0x01) {
                    break;
                }

                //now process the data that was sent to us
                if ((b1 == heart_beat_id[0]) && (b2 == heart_beat_id[1])){ //heart beat id tag
                    outbound_heart_beats--;
                } else if ((b1 == ack_id[0]) && (b2 == ack_id[1])){ //an ack id
                } else {
                    break;
                }
            }
            throwBrokenSocket();
        }
    }

    public void restartSocket(){
        connectionEvent(1);

        outbound_heart_beats = 0;

        //close the previous socket now that it's broken/being restarted
        killSocket();

        //make sure socket thread has joined before throwing off a new one
        try {
            SocketThread.join();
        } catch (InterruptedException e){
            e.printStackTrace();
        }

        //start a new socket thread
        SocketThread = new Thread(new SocketThread());
        SocketThread.start();
    }

    public void killSocket(){
        try {
            Log.d(TAG, "Closing socket, input, serverSocket, etc.");
            if (serverSocket != null && (!serverSocket.isClosed())) {
                serverSocket.close();
                serverSocket = null;
            }
            if (socket != null){
                socket.close();
                socket = null;
            }
            if (output != null){
                output.close();
                output = null;
            }
            if (input != null){
                input.close();
                input = null;
            }
        } catch (IOException e) {
            Log.d(TAG, "killSocket failed");
            e.printStackTrace();
        }
    }


    public byte[] my_int_to_bb_be(int myInteger){
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(myInteger).array();
    }

    public void sendBytes(byte[] id, byte [] data){
        //first, send hello
        byte [] hello = {0x01, 0x02, 0x03};
        //then send length of body
        byte[] len;
        if (data != null) {
            len = my_int_to_bb_be(data.length);
        } else {
            len = my_int_to_bb_be(0);
        }
        //then send id of message type
        byte [] msg_id = id;
        //then send data
        byte [] body = data;
        //then send end tag - eventually make this unique to the image
        byte [] goodbye = {0x3, 0x2, 0x1};
        //combine those into a payload
        ByteArrayOutputStream outputStream;
        try {
            outputStream = new ByteArrayOutputStream();
            outputStream.write(hello);
            outputStream.write(len);
            outputStream.write(msg_id);
            if (body != null) {
                outputStream.write(body);
            }
            outputStream.write(goodbye);
        } catch (IOException e){
            connectionEvent(0);
            return;
        }
        byte [] payload = outputStream.toByteArray();

        //send it in a background thread
        //new Thread(new SendThread(payload)).start();
        queue.add(payload);
    }

    //this sends messages
    class SendThread implements Runnable {
        SendThread() {
        }
        @Override
        public void run() {
            queue.clear();
            while (true){
                if (killme){
                    return;
                }
                if (mConnectState != 2){
                    break;
                }
                if (queue.size() > 10){
                    break;
                }
                byte [] data;
                try {
                    data = queue.poll(100, TimeUnit.MILLISECONDS); //block until there is something we can pull out to send
                    if (data == null){
                        continue;
                    }
                } catch (InterruptedException e){
                    e.printStackTrace();
                    break;
                }
                try {
                    output.write(data);           // write the message
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                    break;
                }
            }
            throwBrokenSocket();
        }
    }

    public  void throwBrokenSocket(){
        if (mConnectState == 2){
            connectionEvent(0);
        }
    }
    //^^^ SOCKET STUFF

    public int getConnectionState(){
        return mConnectState;
    }

    public void destroy(){
        killme = true;

        //kill AudioSystem
        audioSystem.destroy();

        //kill asgWebSocket
        asgWebSocket.destroy();

        //stop sending heart beats
        heart_beat_handler.removeCallbacksAndMessages(null);

        //stop advertising broadcasting IP
        if (adv_handler != null) {
            adv_handler.removeCallbacksAndMessages(null);
        }

        //stop sockets
        killSocket();

        //kill this socket
        try {
            Log.i(TAG, "SOCKETTHREAD TRYNA JOIN");
            if (SocketThread != null) {
                SocketThread.join();
            }
            Log.i(TAG, "SOCKETTHREAD JOINED");
            Log.i(TAG, "SENDTTHREAD TRYNA JOIN");
            if (SendThread != null) {
                SendThread.join();
            }
            Log.i(TAG, "SENDTTHREAD JOINED");
            Log.i(TAG, "RECEIVE THREAD TRYNA JOIN");
            if (ReceiveThread != null) {
                ReceiveThread.join();
            }
            Log.i(TAG, "RECEIVE THREAD JOINED");
        } catch (InterruptedException e){
            e.printStackTrace();
            Log.d(TAG, "Error waiting for threads to joing");
        }
    }

    public void displayReferenceCardSimple(String title, String body){
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.REFERENCE_CARD_SIMPLE_VIEW);
            commandResponseObject.put(MessageTypes.REFERENCE_CARD_SIMPLE_VIEW_TITLE, title);
            commandResponseObject.put(MessageTypes.REFERENCE_CARD_SIMPLE_VIEW_BODY, body);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void stopScrollingTextViewMode() {
        Log.d(TAG, "STOP SCROLLING TEXT VIEW");
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.SCROLLING_TEXT_VIEW_STOP);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void startScrollingTextViewMode(String title){
        super.startScrollingTextViewMode(title);
        Log.d(TAG, "START SCROLLING TEXT VIEW");
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.SCROLLING_TEXT_VIEW_START);
            commandResponseObject.put(MessageTypes.SCROLLING_TEXT_VIEW_TITLE, title);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void scrollingTextViewIntermediateText(String text){
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.SCROLLING_TEXT_VIEW_INTERMEDIATE);
            commandResponseObject.put(MessageTypes.SCROLLING_TEXT_VIEW_TEXT, text);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }

    }

    public void scrollingTextViewFinalText(String text){
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.SCROLLING_TEXT_VIEW_FINAL);
            commandResponseObject.put(MessageTypes.SCROLLING_TEXT_VIEW_TEXT, text);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void showHomeScreen(){
        try{
            //build json object to send command result
            JSONObject commandResponseObject = new JSONObject();
            commandResponseObject.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.ACTION_SWITCH_MODES);
            commandResponseObject.put(MessageTypes.NEW_MODE, MessageTypes.MODE_HOME);

            //send the command result to web socket, to send to asg
            dataObservable.onNext(commandResponseObject);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void displayPromptView(String prompt, String [] options){
        //generate args list
        if (options != null) {

            //required args
//            try{
//                JSONArray argsList = new JSONArray();
//                for (String s : options) {
//                    argsList.put(s);
//                }
//                JSONObject wakeWordFoundEvent = new JSONObject();
//                wakeWordFoundEvent.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.VOICE_COMMAND_STREAM_EVENT);
//                wakeWordFoundEvent.put(MessageTypes.VOICE_COMMAND_STREAM_EVENT_TYPE, MessageTypes.REQUIRED_ARG_EVENT_TYPE);
//                wakeWordFoundEvent.put(MessageTypes.ARG_NAME, prompt);
//                wakeWordFoundEvent.put(MessageTypes.ARG_OPTIONS, argsList);
//                dataObservable.onNext(wakeWordFoundEvent);
//            } catch (JSONException e){
//                e.printStackTrace();
//            }


            //natural language arg
//            try {
//                JSONObject commandFoundEvent = new JSONObject();
//                commandFoundEvent.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.VOICE_COMMAND_STREAM_EVENT);
//                commandFoundEvent.put(MessageTypes.VOICE_COMMAND_STREAM_EVENT_TYPE, MessageTypes.COMMAND_EVENT_TYPE);
//                commandFoundEvent.put(MessageTypes.INPUT_VOICE_COMMAND_NAME, command);
//                commandFoundEvent.put(MessageTypes.INPUT_WAKE_WORD, this.wakeWordGiven);
//                commandFoundEvent.put(MessageTypes.VOICE_ARG_EXPECT_TYPE, MessageTypes.VOICE_ARG_EXPECT_NATURAL_LANGUAGE);
//                dataObservable.onNext(commandFoundEvent);
//            } catch (JSONException e){
//                e.printStackTrace();
//            }

            //found wake word
            JSONArray argsList = new JSONArray();
            for (String s : options) {
                argsList.put(s);
            }
            try {
                JSONObject wakeWordFoundEvent = new JSONObject();
                wakeWordFoundEvent.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.VOICE_COMMAND_STREAM_EVENT);
                wakeWordFoundEvent.put(MessageTypes.VOICE_COMMAND_STREAM_EVENT_TYPE, MessageTypes.WAKE_WORD_EVENT_TYPE);
                wakeWordFoundEvent.put(MessageTypes.VOICE_COMMAND_LIST, argsList.toString());
                wakeWordFoundEvent.put(MessageTypes.INPUT_WAKE_WORD, prompt);
                dataObservable.onNext(wakeWordFoundEvent);
            } catch (JSONException e){
                e.printStackTrace();
            }
        }
    }

    public void showNaturalLanguageCommandScreen(String prompt, String naturalLanguageArgs){
        try {
            JSONObject commandFoundEvent = new JSONObject();
            commandFoundEvent.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.VOICE_COMMAND_STREAM_EVENT);
            commandFoundEvent.put(MessageTypes.VOICE_COMMAND_STREAM_EVENT_TYPE, MessageTypes.COMMAND_EVENT_TYPE);
            commandFoundEvent.put(MessageTypes.INPUT_VOICE_COMMAND_NAME, "myCommand");
            commandFoundEvent.put(MessageTypes.INPUT_WAKE_WORD, "myWakeWord");
            commandFoundEvent.put(MessageTypes.VOICE_ARG_EXPECT_TYPE, MessageTypes.VOICE_ARG_EXPECT_NATURAL_LANGUAGE);
            dataObservable.onNext(commandFoundEvent);
        } catch (JSONException e){
            e.printStackTrace();
        }
    }

    public void updateNaturalLanguageCommandScreen(String naturalLanguageArgs){
        try {
                JSONObject commandFoundEvent = new JSONObject();
                commandFoundEvent.put(MessageTypes.MESSAGE_TYPE_LOCAL, MessageTypes.VOICE_COMMAND_STREAM_EVENT);
                commandFoundEvent.put(MessageTypes.VOICE_COMMAND_STREAM_EVENT_TYPE, MessageTypes.COMMAND_ARGS_EVENT_TYPE);
                commandFoundEvent.put(MessageTypes.INPUT_VOICE_STRING, naturalLanguageArgs);
                dataObservable.onNext(commandFoundEvent);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
