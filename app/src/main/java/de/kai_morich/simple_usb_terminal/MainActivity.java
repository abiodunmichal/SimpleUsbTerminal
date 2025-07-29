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
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.kai_morich.simple_usb_terminal.device.UsbSerialDevice;
import de.kai_morich.simple_usb_terminal.device.UsbSerialInterface;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final int STORAGE_PERMISSION_REQUEST = 1002;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    private TextView debugText;
    private UsbSerialDevice serialDevice;
    private Handler handler = new Handler();

    private YuvToRgbConverter yuvToRgbConverter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        debugText = findViewById(R.id.debugText);

        yuvToRgbConverter = new YuvToRgbConverter(this);

        requestPermissions();

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        setupUsbConnection();
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, CAMERA_PERMISSION_REQUEST);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(128, 128))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {
                    Bitmap bitmap = imageToBitmap(image);
                    float brightness = calculateBrightness(bitmap);
                    analyzeFrame(brightness);
                    image.close();
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageAnalysis);
            } catch (Exception e) {
                e.printStackTrace();
                appendToLog("Camera start failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private Bitmap imageToBitmap(ImageProxy image) {
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        yuvToRgbConverter.yuvToRgb(image, bitmap);
        return bitmap;
    }

    private float calculateBrightness(Bitmap bitmap) {
        long sum = 0;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        for (int x = width / 4; x < 3 * width / 4; x++) {
            for (int y = height / 4; y < 3 * height / 4; y++) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                sum += (r + g + b) / 3;
            }
        }
        return sum / ((width / 2) * (height / 2));
    }

    private void analyzeFrame(float brightness) {
        appendToLog("Brightness: " + brightness);

        if (brightness < 40) {
            sendCommand("b");
            appendToLog("Obstacle detected! Moving back");
        } else {
            sendCommand("f");
            appendToLog("Path clear. Moving forward");
        }
    }

    private void setupUsbConnection() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, permissionIntent);
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            serialDevice = UsbSerialDevice.createUsbSerialDevice(device);
                            if (serialDevice != null) {
                                serialDevice.open();
                                serialDevice.setBaudRate(9600);
                                appendToLog("Serial connection opened");
                            }
                        }
                    } else {
                        appendToLog("USB permission denied");
                    }
                }
            }
        }
    };

    private void sendCommand(String command) {
        if (serialDevice != null) {
            serialDevice.write(command.getBytes());
        }
    }

    private void appendToLog(String message) {
        runOnUiThread(() -> {
            debugText.append(message + "\n");
            writeLogToFile(message);
        });
    }

    private void writeLogToFile(String message) {
        try {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "robot_log.txt");
            FileOutputStream fos = new FileOutputStream(file, true);
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            fos.write((timestamp + " - " + message + "\n").getBytes());
            fos.close();
        } catch (Exception e) {
            Log.e("LogFile", "Error writing log: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serialDevice != null) {
            serialDevice.close();
        }
        unregisterReceiver(usbReceiver);
    }
}
