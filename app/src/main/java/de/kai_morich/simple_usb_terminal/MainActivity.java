// [UNCHANGED IMPORTS]
package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

// ✅ Step 1: TensorFlow Lite and model-loading imports
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import org.tensorflow.lite.DataType;

import android.graphics.Bitmap;
import android.content.res.AssetFileDescriptor;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final int REQUEST_CAMERA_PERMISSION = 10;
    private PreviewView previewView;
    private TextView debugText;

    private UsbSerialPort serialPort;
    private UsbManager usbManager;
    private char lastCommand = '-';
    private boolean obstacleDetected = false;
    private boolean scanning = false;
    private int leftScan = 0;
    private int rightScan = 0;

    // ✅ Step 2: MiDaS model interpreter and input size
    private Interpreter tflite;
    private final int INPUT_WIDTH = 256;
    private final int INPUT_HEIGHT = 256;

    private final StringBuilder fullLog = new StringBuilder();

    private void appendToLog(String msg) {
        Log.d("AppLog", msg);
        runOnUiThread(() -> {
            fullLog.append(msg).append("\n");
            String[] lines = fullLog.toString().split("\n");
            if (lines.length > 20) {
                StringBuilder trimmed = new StringBuilder();
                for (int i = lines.length - 20; i < lines.length; i++) {
                    trimmed.append(lines[i]).append("\n");
                }
                fullLog.setLength(0);
                fullLog.append(trimmed);
            }
            debugText.setText(fullLog.toString());
        });
    }

    private YuvToRgbConverter converter;
    private Bitmap bitmapBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();

        previewView = findViewById(R.id.previewView);
        debugText = findViewById(R.id.debugText);

        appendToLog("App started");

        // ✅ Init converter and allocate bitmap buffer
        converter = new YuvToRgbConverter(this);

        // ✅ Step 3: Load the MiDaS model
        try {
            AssetFileDescriptor fileDescriptor = getAssets().openFd("midas_small.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            MappedByteBuffer modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
            tflite = new Interpreter(modelBuffer);
            appendToLog("✅ MiDaS model loaded from assets.");
        } catch (Exception e) {
            appendToLog("❌ Failed to load model: " + e.getMessage());
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
        } else {
            startCamera();
        }

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (!drivers.isEmpty()) {
            UsbSerialDriver driver = drivers.get(0);
            UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
            if (connection != null) {
                serialPort = driver.getPorts().get(0);
                try {
                    serialPort.open(connection);
                    serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    Toast.makeText(this, "Serial connected", Toast.LENGTH_SHORT).show();
                    appendToLog("Serial connected at 9600 baud");
                } catch (Exception e) {
                    Log.e("Serial", "Error opening serial port", e);
                    appendToLog("Error opening serial port: " + e.getMessage());
                }
            } else {
                Toast.makeText(this, "USB permission denied", Toast.LENGTH_SHORT).show();
                appendToLog("USB permission denied or device not found");
            }
        }
    }

    private void startCamera() {
        ListenableFuture cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = (ProcessCameraProvider) cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(
                        Executors.newSingleThreadExecutor(),
                        image -> {
                            if (bitmapBuffer == null) {
                                bitmapBuffer = Bitmap.createBitmap(
                                        image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                            }
                            detectObstacles(image);
                            image.close();
                        });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                appendToLog("Camera active");

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "Camera start failed", e);
                appendToLog("Camera start failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void detectObstacles(ImageProxy image) {
        // ✅ Step 4: Convert to Bitmap and prepare for MiDaS
        converter.yuvToRgb(image, bitmapBuffer);

        Bitmap resized = Bitmap.createScaledBitmap(bitmapBuffer, INPUT_WIDTH, INPUT_HEIGHT, true);
        TensorImage inputImage = TensorImage.fromBitmap(resized);

        // ✅ Step 5: Run the model on inputImage
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, INPUT_HEIGHT, INPUT_WIDTH, 1}, DataType.FLOAT32);
        tflite.run(inputImage.getBuffer(), outputBuffer.getBuffer());

        float[] depthArray = outputBuffer.getFloatArray();
        appendToLog("🕳 Depth prediction complete. Values: [" + depthArray[0] + ", ...]");
// ✅ Step 6: Extract center depth
    int centerIndex = (INPUT_HEIGHT / 2) * INPUT_WIDTH + (INPUT_WIDTH / 2);
    float centerDepth = depthArray[centerIndex];
    appendToLog("📏 Estimated center depth: " + centerDepth);
if (centerDepth < 0.3f) {
    appendToLog("🛑 Obstacle too close — going backward");
    sendCommand('b');
} else {
    appendToLog("✅ Path clear — moving forward");
    sendCommand('f');
}

    }

    private void sendCommand(char command) {
        if (serialPort != null) {
            try {
                serialPort.write(new byte[]{(byte) command}, 100);
                lastCommand = command;
                appendToLog("Sent command: " + command);
            } catch (Exception e) {
                Log.e("SerialCommand", "Failed to send command", e);
                appendToLog("Error sending command: " + e.getMessage());
            }
        } else {
            Log.e("SerialCommand", "Serial port not available");
            appendToLog("Serial port not available");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
                appendToLog("Camera permission denied");
            }
        }
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null) terminal.status("USB device detected");
            appendToLog("USB device attached");
        }
        super.onNewIntent(intent);
    }
    }
