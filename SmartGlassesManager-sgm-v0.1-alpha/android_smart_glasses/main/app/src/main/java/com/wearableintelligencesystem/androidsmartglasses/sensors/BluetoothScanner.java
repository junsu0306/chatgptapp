package com.wearableintelligencesystem.androidsmartglasses.sensors;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

public class BluetoothScanner {
    private static final String TAG = "WearableAI_BluetoothScanner";

    private Muse myMuse;
    private Context mContext;

    public static String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private boolean mScanning;
    private Handler mHandler;

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_BLUETOOTH_SCAN = 1001; // 권한 요청 코드
    // Stops scanning after 90 seconds.
    private static final long SCAN_PERIOD = 90000;

    public BluetoothScanner(Context context) {
        mHandler = new Handler();
        mContext = context;

        // Use this check to determine whether BLE is supported on the device.
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.d(TAG, "BLUETOOTH NOT AVAILABLE");
        }

        // Initializes a Bluetooth adapter.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Ensures Bluetooth is enabled on the device.
        if (!mBluetoothAdapter.isEnabled()) {
            Log.d(TAG, "BLUETOOTH IS DISABLED ON DEVICE");
        }

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Log.d(TAG, "BLUETOOTH NOT AVAILABLE");
            return;
        }
    }

    // Method to check permissions
    private boolean hasScanPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Android 12 이하 버전에서는 권한 요청이 필요하지 않음
    }

    // Method to request permissions
    private void requestScanPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions((Activity) mContext, new String[]{Manifest.permission.BLUETOOTH_SCAN}, REQUEST_BLUETOOTH_SCAN);
        }
    }

    public void startScan() {
        if (hasScanPermission()) {
            scanLeDevice(true);
        } else {
            Log.d(TAG, "BLUETOOTH_SCAN permission not granted. Requesting permission...");
            requestScanPermission();
        }
    }

    public void stopScan() {
        if (hasScanPermission()) {
            scanLeDevice(false);
        } else {
            Log.d(TAG, "BLUETOOTH_SCAN permission not granted. Requesting permission...");
            requestScanPermission();
        }
    }

    private void scanLeDevice(final boolean enable) {
        Log.d(TAG, "Bluetooth scanning...");
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    stopBluetoothLeScan(); // Stop scanning after the period
                }
            }, SCAN_PERIOD);

            mScanning = true;
            startBluetoothLeScan(); // Start scanning
        } else {
            mScanning = false;
            stopBluetoothLeScan(); // Stop scanning manually
        }
    }

    // Start Bluetooth LE scan with permission check and exception handling
    private void startBluetoothLeScan() {
        try {
            if (hasScanPermission()) {
                mBluetoothAdapter.startLeScan(mLeScanCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Missing BLUETOOTH_SCAN permission.", e);
        }
    }

    // Stop Bluetooth LE scan with permission check and exception handling
    private void stopBluetoothLeScan() {
        try {
            if (hasScanPermission()) {
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Missing BLUETOOTH_SCAN permission.", e);
        }
    }

    // Device scan callback.
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    String deviceName = null;

                    try {
                        // Android 12(API 레벨 31) 이상에서는 BLUETOOTH_CONNECT 권한 확인 필요
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                deviceName = device.getName(); // 권한이 있으면 getName() 호출
                            } else {
                                Log.d(TAG, "BLUETOOTH_CONNECT permission not granted.");
                                ActivityCompat.requestPermissions((Activity) mContext, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_SCAN);
                            }
                        } else {
                            // Android 12 이하에서는 바로 호출 가능
                            deviceName = device.getName();
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "SecurityException: Missing BLUETOOTH_CONNECT permission.", e);
                    }

                    if (deviceName != null && deviceName.toLowerCase().contains("muse")) {
                        Log.d(TAG, "FOUND MUSE: " + deviceName);
                        if (mScanning) {
                            stopBluetoothLeScan(); // Stop scanning once the device is found
                            mScanning = false;
                            // Needs to happen in a new thread/handler
                            myMuse = new Muse(mContext, deviceName, device.getAddress());
                            myMuse.run();
                        }
                    }
                }
            };

}

