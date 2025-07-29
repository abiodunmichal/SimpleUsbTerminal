
‎package de.kai_morich.simple_usb_terminal;
‎
‎import android.Manifest;
‎import android.content.Intent;
‎import android.content.pm.PackageManager;
‎import android.hardware.usb.UsbDeviceConnection;
‎import android.hardware.usb.UsbManager;
‎import android.os.Bundle;
‎import android.util.Log;
‎import android.widget.TextView;
‎import android.widget.Toast;
‎
‎import androidx.annotation.NonNull;
‎import androidx.appcompat.app.AppCompatActivity;
‎import androidx.appcompat.widget.Toolbar;
‎import androidx.camera.core.CameraSelector;
‎import androidx.camera.core.ImageAnalysis;
‎import androidx.camera.core.ImageProxy;
‎import androidx.camera.core.Preview;
‎import androidx.camera.lifecycle.ProcessCameraProvider;
‎import androidx.camera.view.PreviewView;
‎import androidx.core.app.ActivityCompat;
‎import androidx.core.content.ContextCompat;
‎import androidx.fragment.app.FragmentManager;
‎
‎import com.google.common.util.concurrent.ListenableFuture;
‎import com.hoho.android.usbserial.driver.UsbSerialDriver;
‎import com.hoho.android.usbserial.driver.UsbSerialPort;
‎import com.hoho.android.usbserial.driver.UsbSerialProber;
‎
‎import java.nio.ByteBuffer;
‎import java.util.List;
‎import java.util.concurrent.ExecutionException;
‎import java.util.concurrent.Executors;
‎
‎public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {
‎
‎    private static final int REQUEST_CAMERA_PERMISSION = 10;
‎    private PreviewView previewView;
‎    private TextView debugText;
‎
‎    private UsbSerialPort serialPort;
‎    private UsbManager usbManager;
‎    private char lastCommand = '-';
‎    private boolean obstacleDetected = false;
‎    private boolean scanning = false;
‎    private int leftScan = 0;
‎    private int rightScan = 0;
‎
‎    private final StringBuilder fullLog = new StringBuilder();
‎    private void appendToLog(String msg) {
‎        Log.d("AppLog", msg);
‎        runOnUiThread(() -> {
‎            fullLog.append(msg).append("\n");
‎            String[] lines = fullLog.toString().split("\n");
‎            if (lines.length > 20) {
‎                StringBuilder trimmed = new StringBuilder();
‎                for (int i = lines.length - 20; i < lines.length; i++) {
‎                    trimmed.append(lines[i]).append("\n");
‎                }
‎                fullLog.setLength(0);
‎                fullLog.append(trimmed);
‎            }
‎            debugText.setText(fullLog.toString());
‎        });
‎    }
‎
‎    @Override
‎    protected void onCreate(Bundle savedInstanceState) {
‎        super.onCreate(savedInstanceState);
‎        setContentView(R.layout.activity_main);
‎
‎        Toolbar toolbar = findViewById(R.id.toolbar);
‎        setSupportActionBar(toolbar);
‎        getSupportFragmentManager().addOnBackStackChangedListener(this);
‎        if (savedInstanceState == null)
‎            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
‎        else
‎            onBackStackChanged();
‎
‎        previewView = findViewById(R.id.previewView);
‎        debugText = findViewById(R.id.debugText);
‎
‎        appendToLog("App started");
‎
‎        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
‎                != PackageManager.PERMISSION_GRANTED) {
‎            ActivityCompat.requestPermissions(this,
‎                    new String[]{Manifest.permission.CAMERA},
‎                    REQUEST_CAMERA_PERMISSION);
‎        } else {
‎            startCamera();
‎        }
‎
‎        usbManager = (UsbManager) getSystemService(USB_SERVICE);
‎        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
‎        if (!drivers.isEmpty()) {
‎            UsbSerialDriver driver = drivers.get(0);
‎            UsbDeviceConnection connection = usbManager.openDevice(driver.getDevice());
‎            if (connection != null) {
‎                serialPort = driver.getPorts().get(0);
‎                try {
‎                    serialPort.open(connection);
‎                    serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
‎                    Toast.makeText(this, "Serial connected", Toast.LENGTH_SHORT).show();
‎                    appendToLog("Serial connected at 9600 baud");
‎                } catch (Exception e) {
‎                    Log.e("Serial", "Error opening serial port", e);
‎                    appendToLog("Error opening serial port: " + e.getMessage());
‎                }
‎            } else {
‎                Toast.makeText(this, "USB permission denied", Toast.LENGTH_SHORT).show();
‎                appendToLog("USB permission denied or device not found");
‎            }
‎        }
‎    }
‎
‎    private void startCamera() {
‎        ListenableFuture cameraProviderFuture = ProcessCameraProvider.getInstance(this);
‎        cameraProviderFuture.addListener(() -> {
‎            try {
‎                ProcessCameraProvider cameraProvider = (ProcessCameraProvider) cameraProviderFuture.get();
‎                Preview preview = new Preview.Builder().build();
‎                preview.setSurfaceProvider(previewView.getSurfaceProvider());
‎
‎                CameraSelector cameraSelector = new CameraSelector.Builder()
‎                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
‎                        .build();
‎
‎                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
‎                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
‎                        .build();
‎
‎                imageAnalysis.setAnalyzer(
‎                        Executors.newSingleThreadExecutor(),
‎                        image -> {
‎                            detectObstacles(image);
‎                            image.close();
‎                        });
‎
‎                cameraProvider.unbindAll();
‎                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
‎                appendToLog("Camera active");
‎
‎            } catch (ExecutionException | InterruptedException e) {
‎                Log.e("CameraX", "Camera start failed", e);
‎                appendToLog("Camera start failed: " + e.getMessage());
‎            }
‎        }, ContextCompat.getMainExecutor(this));
‎    }
‎
‎    private void detectObstacles(ImageProxy image) {
‎        ImageProxy.PlaneProxy[] planes = image.getPlanes();
‎        if (planes.length > 0) {
‎            ByteBuffer buffer = planes[0].getBuffer();
‎            byte[] bytes = new byte[buffer.remaining()];
‎            buffer.get(bytes);
‎
‎            int width = image.getWidth();
‎            int height = image.getHeight();
‎            int rowStride = planes[0].getRowStride();
‎            int thirdWidth = width / 3;
‎
‎            int[] brightness = new int[3];
‎            int[] counts = new int[3];
‎
‎            for (int y = 0; y < height; y += 10) {
‎                for (int x = 0; x < width; x += 10) {
‎                    int offset = y * rowStride + x;
‎                    if (offset >= bytes.length) continue;
‎
‎                    int pixel = bytes[offset] & 0xFF;
‎                    if (x < thirdWidth) {
‎                        brightness[0] += pixel;
‎                        counts[0]++;
‎                    } else if (x < 2 * thirdWidth) {
‎                        brightness[1] += pixel;
‎                        counts[1]++;
‎                    } else {
‎                        brightness[2] += pixel;
‎                        counts[2]++;
‎                    }
‎                }
‎            }
‎
‎            int leftAvg = counts[0] > 0 ? brightness[0] / counts[0] : 0;
‎            int centerAvg = counts[1] > 0 ? brightness[1] / counts[1] : 0;
‎            int rightAvg = counts[2] > 0 ? brightness[2] / counts[2] : 0;
‎
‎            String debugMsg = "L: " + leftAvg + "  C: " + centerAvg + "  R: " + rightAvg + "  →  " + lastCommand;
‎            appendToLog(debugMsg);
‎
‎            if (!scanning && !obstacleDetected && centerAvg < 50) {
‎                obstacleDetected = true;
‎                sendCommand('b');
‎                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
‎                sendCommand('l');
‎                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
‎                leftScan = centerAvg;
‎                sendCommand('r');
‎                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
‎                rightScan = centerAvg;
‎
‎                if (leftScan > rightScan && leftScan > 40) {
‎                    sendCommand('l');
‎                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
‎                    sendCommand('f');
‎                } else if (rightScan >= leftScan && rightScan > 40) {
‎                    sendCommand('f');
‎                } else {
‎                    sendCommand('b');
‎                }
‎
‎                obstacleDetected = false;
‎            } else if (!obstacleDetected && centerAvg >= 50) {
‎                sendCommand('f');
‎            }
‎        }
‎    }
‎
‎    private void sendCommand(char command) {
‎        if (serialPort != null) {
‎            try {
‎                serialPort.write(new byte[]{(byte) command}, 100);
‎                lastCommand = command;
‎                appendToLog("Sent command: " + command);
‎            } catch (Exception e) {
‎                Log.e("SerialCommand", "Failed to send command", e);
‎                appendToLog("Error sending command: " + e.getMessage());
‎            }
‎        } else {
‎            Log.e("SerialCommand", "Serial port not available");
‎            appendToLog("Serial port not available");
‎        }
‎    }
‎
‎    @Override
‎    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
‎        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
‎        if (requestCode == REQUEST_CAMERA_PERMISSION) {
‎            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
‎                startCamera();
‎            } else {
‎                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
‎                appendToLog("Camera permission denied");
‎            }
‎        }
‎    }
‎
‎    @Override
‎    public void onBackStackChanged() {
‎        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
‎    }
‎
‎    @Override
‎    public boolean onSupportNavigateUp() {
‎        onBackPressed();
‎        return true;
‎    }
‎
‎    @Override
‎    protected void onNewIntent(Intent intent) {
‎        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
‎            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
‎            if (terminal != null) terminal.status("USB device detected");
‎            appendToLog("USB device attached");
‎        }
‎        super.onNewIntent(intent);
‎    }
‎ }
‎
