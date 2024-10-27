package com.wearableintelligencesystem.androidsmartglasses.sensors;

//thanks to https://github.com/aahlenst/android-audiorecord-sample/blob/master/src/main/java/com/example/audiorecord/BluetoothRecordActivity.java


import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sample that demonstrates how to record from a Bluetooth HFP microphone using {@link AudioRecord}.
 */
public class BluetoothMic extends Context {

    private static final String TAG = "WearableAi_" + BluetoothMic.class.getCanonicalName();

    private static final int SAMPLING_RATE_IN_HZ = 16000;

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * Factor by that the minimum buffer size is multiplied. The bigger the factor is the less
     * likely it is that samples will be dropped, but more memory will be used. The minimum buffer
     * size is determined by {@link AudioRecord#getMinBufferSize(int, int, int)} and depends on the
     * recording settings.
     */
    private final static float BUFFER_SIZE_SECONDS = 0.2f;
    private static final int BUFFER_SIZE_FACTOR = 2;
    private final int bufferSize;

    private boolean bluetoothAudio = false; //are we using local audio or bluetooth audio?
    private int retries = 0;
    private int retryLimit = 3;

    private Handler mHandler;

    /**
     * Size of the buffer where the audio data is stored by Android
     */
//    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
//            CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR;

    /**
     * Signals whether a recording is in progress (true) or not (false).
     */
    private final AtomicBoolean recordingInProgress = new AtomicBoolean(false);

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {

        private BluetoothState bluetoothState = BluetoothState.UNAVAILABLE;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) {
                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                switch (state) {
                    case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                        Log.i(TAG, "Bluetooth HFP Headset is connected");
                        //handleBluetoothStateChange(BluetoothState.AVAILABLE);
                        if (mIsStarting)
                        {
                            // When the device is connected before the application starts,
                            // ACTION_ACL_CONNECTED will not be received, so call onHeadsetConnected here
                            mIsStarting = false;
                        }

                        if (mIsCountDownOn)
                        {
                            mIsCountDownOn = false;
                            mCountDown.cancel();
                        }
                        bluetoothAudio = true;
                        startRecording();
                        break;
                    case AudioManager.SCO_AUDIO_STATE_CONNECTING:
                        Log.i(TAG, "Bluetooth HFP Headset is connecting");
                        handleBluetoothStateChange(BluetoothState.UNAVAILABLE);
                    case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                        Log.i(TAG, "Bluetooth HFP Headset is disconnected");
                        //handleBluetoothStateChange(BluetoothState.UNAVAILABLE);
                        // Always receive SCO_AUDIO_STATE_DISCONNECTED on call to startBluetooth()
                        // which at that stage we do not want to do anything. Thus the if condition.
                        break;
                    case AudioManager.SCO_AUDIO_STATE_ERROR:
                        Log.i(TAG, "Bluetooth HFP Headset is in error state");
                        handleBluetoothStateChange(BluetoothState.UNAVAILABLE);
                        break;
                }
            } else if (action.equals(BluetoothDevice.ACTION_ACL_CONNECTED)){
                    Log.i(TAG, "New bluetooth device is available");
                    handleNewBluetoothDevice();
            } else if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)){
                Log.i(TAG, "Bluetooth device was disconnected");
                handleDisconnectBluetoothDevice();
            }
    }

    private void handleBluetoothStateChange(BluetoothState state) {
            if (bluetoothState == state) {
                return;
            }

            bluetoothState = state;
            bluetoothStateChanged(state);
        }

        private void handleNewBluetoothDevice(){
            //check if the new device is a headset, if so, connect
            retries = 0; //reset retries as there is a new device
//            if (!bluetoothAudio){
//                activateBluetoothSco();
//            }

            // start bluetooth Sco audio connection.
            // Calling startBluetoothSco() always returns faIL here,
            // that why a count down timer is implemented to call
            // startBluetoothSco() in the onTick.
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            mIsCountDownOn = true;
            mCountDown.start();
        }

        private void handleDisconnectBluetoothDevice() {
            if (mIsCountDownOn)
            {
                mIsCountDownOn = false;
                mCountDown.cancel();
            }
            bluetoothAudio = false;
            deactivateBluetoothSco();
            startRecording(); //if disconnected, now we should switch to recording on local mic
        }
    };

    private AudioRecord recorder = null;

    private AudioManager audioManager;
    private boolean mIsCountDownOn = false;
    private boolean mIsStarting = false;

    private Thread recordingThread = null;

    private Context mContext;

    private AudioChunkCallback mChunkCallback;

//    private Button startButton;
//
//    private Button stopButton;
//
//    private Button bluetoothButton;

    public BluetoothMic(Context context, AudioChunkCallback chunkCallback) {
        bufferSize = Math.round(SAMPLING_RATE_IN_HZ * BUFFER_SIZE_SECONDS);

        // need for audio sco, see mBroadcastReceiver
        mIsStarting = true;

        //setup context
        mContext = context;

        mChunkCallback = chunkCallback;

        //setup handler
        mHandler = new Handler();

        //listen for bluetooth HFP events
        audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mContext.registerReceiver(bluetoothStateReceiver, new IntentFilter(
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        mContext.registerReceiver(bluetoothStateReceiver,
                new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
        mContext.registerReceiver(bluetoothStateReceiver,
                new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));

        //first, start recording on local microphone
        bluetoothAudio = false;
        startRecording();
        //the, immediately try to startup the SCO connection
        mIsCountDownOn = true;
        mCountDown.start();
    }

    private void startRecording() {

        //if alraedy recording, stop previous and start a new one
        if (recorder != null) {
            stopRecording();
        }
        if (bluetoothAudio) {
            Log.d(TAG, "Starting recording on Bluetooth Microphone");
        } else {
            Log.d(TAG, "Starting recording on local microphone");
        }

        // Depending on the device one might has to change the AudioSource, e.g. to DEFAULT
        // or VOICE_COMMUNICATION

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 2);

        recorder.startRecording();

        recordingInProgress.set(true);

        recordingThread = new Thread(new RecordingRunnable(), "Recording Thread");
        recordingThread.start();
    }

    private void stopRecording() {
        Log.d(TAG, "Stopping audio recording");
        if (null == recorder) {
            return;
        }

        recordingInProgress.set(false);

        recorder.stop();

        recorder.release();

        recorder = null;

        recordingThread = null;
    }

    private void activateBluetoothSco() {
        Log.d(TAG, "Activating Bluetooth SCO");

        retries += 1;

        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(TAG, "SCO ist not available, recording is not possible");
            return;
        }

        if (audioManager.isBluetoothScoOn()) {
            audioManager.stopBluetoothSco();
        }
        audioManager.startBluetoothSco();
    }

    private void deactivateBluetoothSco(){
        Log.d(TAG, "Deactivating Bluetooth SCO");
        audioManager.stopBluetoothSco();
    }

    private void bluetoothStateChanged(BluetoothState state) {
        Log.i(TAG, "Bluetooth state changed to:" + state);

        if (BluetoothState.UNAVAILABLE == state && recordingInProgress.get()) {
            bluetoothAudio = false;
            stopRecording();
            deactivateBluetoothSco();
        } else if (BluetoothState.AVAILABLE == state && !recordingInProgress.get()){
            bluetoothAudio = true;
            startRecording();
        } else if (BluetoothState.AVAILABLE == state && !bluetoothAudio){
            bluetoothAudio = true;
            startRecording();
        }
    }

    @Override
    public AssetManager getAssets() {
        return null;
    }

    @Override
    public Resources getResources() {
        return null;
    }

    @Override
    public PackageManager getPackageManager() {
        return null;
    }

    @Override
    public ContentResolver getContentResolver() {
        return null;
    }

    @Override
    public Looper getMainLooper() {
        return null;
    }

    @Override
    public Context getApplicationContext() {
        return null;
    }

    @Override
    public void setTheme(int resid) {

    }

    @Override
    public Resources.Theme getTheme() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return null;
    }

    @Override
    public String getPackageName() {
        return "";
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return null;
    }

    @Override
    public String getPackageResourcePath() {
        return "";
    }

    @Override
    public String getPackageCodePath() {
        return "";
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return false;
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return false;
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return null;
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return null;
    }

    @Override
    public boolean deleteFile(String name) {
        return false;
    }

    @Override
    public File getFileStreamPath(String name) {
        return null;
    }

    @Override
    public File getDataDir() {
        return null;
    }

    @Override
    public File getFilesDir() {
        return null;
    }

    @Override
    public File getNoBackupFilesDir() {
        return null;
    }

    @Nullable
    @Override
    public File getExternalFilesDir(@Nullable String type) {
        return null;
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        return new File[0];
    }

    @Override
    public File getObbDir() {
        return null;
    }

    @Override
    public File[] getObbDirs() {
        return new File[0];
    }

    @Override
    public File getCacheDir() {
        return null;
    }

    @Override
    public File getCodeCacheDir() {
        return null;
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return null;
    }

    @Override
    public File[] getExternalCacheDirs() {
        return new File[0];
    }

    @Override
    public File[] getExternalMediaDirs() {
        return new File[0];
    }

    @Override
    public String[] fileList() {
        return new String[0];
    }

    @Override
    public File getDir(String name, int mode) {
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return null;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, @Nullable DatabaseErrorHandler errorHandler) {
        return null;
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        return false;
    }

    @Override
    public boolean deleteDatabase(String name) {
        return false;
    }

    @Override
    public File getDatabasePath(String name) {
        return null;
    }

    @Override
    public String[] databaseList() {
        return new String[0];
    }

    @Override
    public Drawable getWallpaper() {
        return null;
    }

    @Override
    public Drawable peekWallpaper() {
        return null;
    }

    @Override
    public int getWallpaperDesiredMinimumWidth() {
        return 0;
    }

    @Override
    public int getWallpaperDesiredMinimumHeight() {
        return 0;
    }

    @Override
    public void setWallpaper(Bitmap bitmap) throws IOException {

    }

    @Override
    public void setWallpaper(InputStream data) throws IOException {

    }

    @Override
    public void clearWallpaper() throws IOException {

    }

    @Override
    public void startActivity(Intent intent) {

    }

    @Override
    public void startActivity(Intent intent, @Nullable Bundle options) {

    }

    @Override
    public void startActivities(Intent[] intents) {

    }

    @Override
    public void startActivities(Intent[] intents, Bundle options) {

    }

    @Override
    public void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags) throws IntentSender.SendIntentException {

    }

    @Override
    public void startIntentSender(IntentSender intent, @Nullable Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, @Nullable Bundle options) throws IntentSender.SendIntentException {

    }

    @Override
    public void sendBroadcast(Intent intent) {

    }

    @Override
    public void sendBroadcast(Intent intent, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcast(Intent intent, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcast(@NonNull Intent intent, @Nullable String receiverPermission, @Nullable BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Override
    public void sendBroadcastAsUser(Intent intent, UserHandle user, @Nullable String receiverPermission) {

    }

    @Override
    public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user, @Nullable String receiverPermission, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void sendStickyBroadcast(Intent intent) {

    }

    @Override
    public void sendStickyOrderedBroadcast(Intent intent, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void removeStickyBroadcast(Intent intent) {

    }

    @Override
    public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Override
    public void sendStickyOrderedBroadcastAsUser(Intent intent, UserHandle user, BroadcastReceiver resultReceiver, @Nullable Handler scheduler, int initialCode, @Nullable String initialData, @Nullable Bundle initialExtras) {

    }

    @Override
    public void removeStickyBroadcastAsUser(Intent intent, UserHandle user) {

    }

    @Nullable
    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter, int flags) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, @Nullable String broadcastPermission, @Nullable Handler scheduler) {
        return null;
    }

    @Nullable
    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, @Nullable String broadcastPermission, @Nullable Handler scheduler, int flags) {
        return null;
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {

    }

    @Nullable
    @Override
    public ComponentName startService(Intent service) {
        return null;
    }

    @Nullable
    @Override
    public ComponentName startForegroundService(Intent service) {
        return null;
    }

    @Override
    public boolean stopService(Intent service) {
        return false;
    }

    @Override
    public boolean bindService(Intent service, @NonNull ServiceConnection conn, int flags) {
        return false;
    }

    @Override
    public void unbindService(@NonNull ServiceConnection conn) {

    }

    @Override
    public boolean startInstrumentation(@NonNull ComponentName className, @Nullable String profileFile, @Nullable Bundle arguments) {
        return false;
    }

    @Override
    public Object getSystemService(@NonNull String name) {
        return null;
    }

    @Nullable
    @Override
    public String getSystemServiceName(@NonNull Class<?> serviceClass) {
        return "";
    }

    @Override
    public int checkPermission(@NonNull String permission, int pid, int uid) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingPermission(@NonNull String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingOrSelfPermission(@NonNull String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkSelfPermission(@NonNull String permission) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void enforcePermission(@NonNull String permission, int pid, int uid, @Nullable String message) {

    }

    @Override
    public void enforceCallingPermission(@NonNull String permission, @Nullable String message) {

    }

    @Override
    public void enforceCallingOrSelfPermission(@NonNull String permission, @Nullable String message) {

    }

    @Override
    public void grantUriPermission(String toPackage, Uri uri, int modeFlags) {

    }

    @Override
    public void revokeUriPermission(Uri uri, int modeFlags) {

    }

    @Override
    public void revokeUriPermission(String toPackage, Uri uri, int modeFlags) {

    }

    @Override
    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingUriPermission(Uri uri, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkCallingOrSelfUriPermission(Uri uri, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int checkUriPermission(@Nullable Uri uri, @Nullable String readPermission, @Nullable String writePermission, int pid, int uid, int modeFlags) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void enforceUriPermission(Uri uri, int pid, int uid, int modeFlags, String message) {

    }

    @Override
    public void enforceCallingUriPermission(Uri uri, int modeFlags, String message) {

    }

    @Override
    public void enforceCallingOrSelfUriPermission(Uri uri, int modeFlags, String message) {

    }

    @Override
    public void enforceUriPermission(@Nullable Uri uri, @Nullable String readPermission, @Nullable String writePermission, int pid, int uid, int modeFlags, @Nullable String message) {

    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        return null;
    }

    @Override
    public Context createContextForSplit(String splitName) throws PackageManager.NameNotFoundException {
        return null;
    }

    @Override
    public Context createConfigurationContext(@NonNull Configuration overrideConfiguration) {
        return null;
    }

    @Override
    public Context createDisplayContext(@NonNull Display display) {
        return null;
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        return null;
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return false;
    }

    private class RecordingRunnable implements Runnable {

        @Override
        public void run() {
//            final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            short[] short_buffer = new short[bufferSize];
            ByteBuffer b_buffer = ByteBuffer.allocate(short_buffer.length * 2);

                while (recordingInProgress.get()) {
//                    int result = recorder.read(buffer, BUFFER_SIZE);
                    // read the data into the buffer
                    int result = recorder.read(short_buffer, 0, short_buffer.length);
                    if (result < 0) {
                        Log.d(TAG, "ERROR");
                    }
                    //convert short array to byte array
                    b_buffer.order(ByteOrder.LITTLE_ENDIAN);
                    b_buffer.asShortBuffer().put(short_buffer);
                    //send to audio system
                    mChunkCallback.onSuccess(b_buffer);
                    b_buffer.clear();
                }
        }

        private String getBufferReadFailureReason(int errorCode) {
            switch (errorCode) {
                case AudioRecord.ERROR_INVALID_OPERATION:
                    return "ERROR_INVALID_OPERATION";
                case AudioRecord.ERROR_BAD_VALUE:
                    return "ERROR_BAD_VALUE";
                case AudioRecord.ERROR_DEAD_OBJECT:
                    return "ERROR_DEAD_OBJECT";
                case AudioRecord.ERROR:
                    return "ERROR";
                default:
                    return "Unknown (" + errorCode + ")";
            }
        }
    }

    enum BluetoothState {
        AVAILABLE, UNAVAILABLE
    }

    /**
     * Try to connect to audio headset in onTick.
     */
    private CountDownTimer mCountDown = new CountDownTimer(1000, 300)
    {

        @SuppressWarnings("synthetic-access")
        @Override
        public void onTick(long millisUntilFinished)
        {
            // When this call is successful, this count down timer will be canceled.
            audioManager.startBluetoothSco();

            Log.d(TAG, "\nonTick start bluetooth Sco"); //$NON-NLS-1$
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public void onFinish()
        {
            // Calls to startBluetoothSco() in onStick are not successful.
            // Should implement something to inform user of this failure
            mIsCountDownOn = false;
            audioManager.setMode(AudioManager.MODE_NORMAL);

            //if it fails after n tries, then we should start recording with local microphone
            startRecording();

            Log.d(TAG, "\nonFinish fail to connect to headset audio"); //$NON-NLS-1$
        }
    };
}
