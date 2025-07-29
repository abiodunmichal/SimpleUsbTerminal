package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

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
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager usbIoManager;
    private UsbManager usbManager;
    private PreviewView previewView;
    private TextView debugText;
    private ExecutorService cameraExecutor;
    private File logFile;
    private Handler handler = new Handler();

    private BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            connectToSerialPort(device);
                        }
                    } else {
                        Log.d("USB", "Permission denied for device " + device);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.previewView);
        debugText = findViewById(R.id.debugText);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        // ✅ Fix for Android 14+ Lint Error
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            new IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 1);

        initLogFile();
        setupCamera();
        setupUsb();
    }

    private void setupUsb() {
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (!availableDrivers.isEmpty()) {
            UsbSerialDriver driver = availableDrivers.get(0);
            UsbDevice device = driver.getDevice();
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, permissionIntent);
        } else {
            appendToLog("No USB serial device connected");
        }
    }

    private void connectToSerialPort(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            appendToLog("No driver for device");
            return;
        }
        UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            appendToLog("Cannot open connection");
            return;
        }

        usbSerialPort = driver.getPorts().get(0);
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            appendToLog("Serial port connected");
        } catch (IOException e) {
            appendToLog("Error opening serial port: " + e.getMessage());
        }
    }

    private void sendCommand(String command) {
        try {
            if (usbSerialPort != null) {
                usbSerialPort.write(command.getBytes(), 1000);
                appendToLog("Sent: " + command);
            }
        } catch (IOException e) {
            appendToLog("Error sending command: " + e.getMessage());
        }
    }

    private void setupCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().build();
                imageAnalysis.setAnalyzer(cameraExecutor = Executors.newSingleThreadExecutor(), this::analyzeImage);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                appendToLog("Camera setup failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeImage(@NonNull ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        int sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        final int avgBrightness = sum / data.length;

        runOnUiThread(() -> {
            appendToLog("Brightness: " + avgBrightness);
            if (avgBrightness < 50) {
                sendCommand("b");
                handler.postDelayed(() -> sendCommand("l"), 500);
            } else {
                sendCommand("f");
            }
        });

        image.close();
    }

    private void appendToLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String fullMessage = timestamp + " - " + message;
        debugText.append(fullMessage + "\n");
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(fullMessage + "\n");
        } catch (IOException e) {
            Log.e("Log", "Error writing to log file", e);
        }
    }

    private void initLogFile() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        logFile = new File(downloadsDir, "robot_log.txt");
        try {
            if (!logFile.exists()) logFile.createNewFile();
        } catch (IOException e) {
            Log.e("Log", "Log file creation failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                appendToLog("Error closing serial port: " + e.getMessage());
            }
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
    }
