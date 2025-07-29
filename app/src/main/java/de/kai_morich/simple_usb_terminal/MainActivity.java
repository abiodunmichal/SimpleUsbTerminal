package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "RobotControl";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_USB_PERMISSION = 201;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    private PreviewView previewView;
    private TextView debugText;
    private UsbSerialPort serialPort;
    private UsbManager usbManager;
    private SerialInputOutputManager usbIoManager;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler();
    private YuvToRgbConverter yuvToRgbConverter;
    private File logFile;

    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        debugText = findViewById(R.id.debugText);

        yuvToRgbConverter = new YuvToRgbConverter(this);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        initLogFile();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            startCamera();
        }

        registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        connectToUsbSerial();
    }

    private void initLogFile() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        logFile = new File(downloads, "robot_log_" + timestamp + ".txt");
    }

    private void appendToLog(String message) {
        runOnUiThread(() -> {
            String timestamped = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + message;
            debugText.append(timestamped + "\n");

            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.write(timestamped + "\n");
            } catch (IOException e) {
                Log.e(TAG, "Log write error: " + e.getMessage());
            }
        });
    }

    private void connectToUsbSerial() {
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (availableDrivers.isEmpty()) {
            appendToLog("No USB serial device found.");
            return;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, permissionIntent);
            return;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            appendToLog("Failed to open USB device connection.");
            return;
        }

        serialPort = driver.getPorts().get(0);
        try {
            serialPort.open(connection);
            serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            appendToLog("Serial port opened.");
        } catch (IOException e) {
            appendToLog("Serial port error: " + e.getMessage());
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToUsbSerial();
                        }
                    } else {
                        appendToLog("USB permission denied.");
                    }
                }
            }
        }
    };

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(128, 128))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
            } catch (Exception e) {
                appendToLog("Camera start error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        if (isProcessing) {
            image.close();
            return;
        }
        isProcessing = true;

        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        yuvToRgbConverter.yuvToRgb(image, bitmap);
        image.close();

        int centerX = bitmap.getWidth() / 2;
        int centerY = bitmap.getHeight() / 2;

        int brightness = 0;
        for (int y = centerY - 10; y < centerY + 10; y++) {
            for (int x = centerX - 10; x < centerX + 10; x++) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                brightness += (r + g + b) / 3;
            }
        }
        brightness /= 400;

        appendToLog("Center brightness: " + brightness);

        String command;
        if (brightness < 50) {
            command = "b";
        } else {
            command = "f";
        }

        sendSerialCommand(command);
        handler.postDelayed(() -> isProcessing = false, 500);
    }

    private void sendSerialCommand(String command) {
        if (serialPort != null) {
            try {
                serialPort.write(command.getBytes(), 100);
                appendToLog("Sent: " + command);
            } catch (IOException e) {
                appendToLog("Send failed: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbIoManager != null) {
            usbIoManager.stop();
        }
        try {
            if (serialPort != null) {
                serialPort.close();
            }
        } catch (IOException ignored) {}
        unregisterReceiver(usbReceiver);
        executor.shutdown();
    }
    }
