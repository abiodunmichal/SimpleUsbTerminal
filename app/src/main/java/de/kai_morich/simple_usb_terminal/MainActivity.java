package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final int REQUEST_CAMERA_PERMISSION = 200;
    private ExecutorService cameraExecutor;
    private YuvToRgbConverter yuvToRgbConverter;
    private Interpreter tfLiteInterpreter;

    private TextView debugText;
    private Handler handler = new Handler();
    private boolean isProcessing = false;
    private UsbSerialPort usbSerialPort;

    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        debugText = findViewById(R.id.debugText);
        appendToLog("Initializing...");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            startCamera();
        }

        try {
            MappedByteBuffer tfliteModel = FileUtil.loadMappedFile(this, "midas_small.tflite");
            tfLiteInterpreter = new Interpreter(tfliteModel);
            appendToLog("Model loaded successfully.");
        } catch (IOException e) {
            appendToLog("Failed to load model: " + e.getMessage());
        }

        yuvToRgbConverter = new YuvToRgbConverter(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        logFile = new File(downloadsDir, "robot_log.txt");
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis =
                        new ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isProcessing) {
                        isProcessing = true;
                        processImage(image);
                    } else {
                        image.close();
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                appendToLog("Camera initialization failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImage(ImageProxy image) {
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        yuvToRgbConverter.yuvToRgb(image, bitmap);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 256, 256, true);

        ByteBuffer input = ByteBuffer.allocateDirect(256 * 256 * 3 * 4);
        input.rewind();
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int px = scaled.getPixel(x, y);
                input.putFloat(((px >> 16) & 0xFF) / 255.f);  // R
                input.putFloat(((px >> 8) & 0xFF) / 255.f);   // G
                input.putFloat((px & 0xFF) / 255.f);          // B
            }
        }

        float[][][] output = new float[1][256][256];
        tfLiteInterpreter.run(input, output);

        float centerDepth = output[0][128][128];
        int estimatedCM = (int) (1.0f / (centerDepth + 1e-6f) * 100); // Just an estimate

        appendToLog("Depth estimate: " + estimatedCM + " cm");

        if (estimatedCM < 20) {
            sendCommand("s"); // Stop
            appendToLog("Obstacle detected (<20cm), stopping");
            handler.postDelayed(() -> {
                sendCommand("b"); // Back
                appendToLog("Backing up");
                handler.postDelayed(() -> {
                    sendCommand("r"); // Turn right
                    appendToLog("Turning right");
                    handler.postDelayed(() -> {
                        sendCommand("f"); // Resume forward
                        appendToLog("Moving forward");
                        isProcessing = false;
                    }, 1000);
                }, 1000);
            }, 1000);
        } else {
            sendCommand("f");
            appendToLog("Path clear, moving forward");
            handler.postDelayed(() -> isProcessing = false, 500);
        }

        image.close();
    }

    private void sendCommand(String cmd) {
        if (usbSerialPort != null) {
            usbSerialPort.write(cmd.getBytes());
        }
    }

    private void appendToLog(String message) {
        String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String fullMsg = timeStamp + " - " + message;
        runOnUiThread(() -> debugText.append(fullMsg + "\n"));

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.append(fullMsg).append("\n");
        } catch (IOException e) {
            Log.e("Logger", "Failed to write log: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
            }
        }
    }
    }
