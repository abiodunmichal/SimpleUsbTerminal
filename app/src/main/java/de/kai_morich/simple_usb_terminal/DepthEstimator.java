package de.kai_morich.simple_usb_terminal;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class DepthEstimator {

    private final Interpreter tflite;
    private final int inputWidth = 256;
    private final int inputHeight = 256;
    private final float[][][][] output = new float[1][inputHeight][inputWidth][1];

    public DepthEstimator(AssetManager assetManager, String modelPath) throws IOException {
        MappedByteBuffer modelBuffer = loadModelFile(assetManager, modelPath);
        tflite = new Interpreter(modelBuffer);
        Log.d("DepthEstimator", "Model loaded from assets/" + modelPath);
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public float estimateDepth(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true);

        // Convert bitmap to input tensor (normalized RGB)
        float[][][][] input = new float[1][inputHeight][inputWidth][3];
        for (int y = 0; y < inputHeight; y++) {
            for (int x = 0; x < inputWidth; x++) {
                int pixel = resized.getPixel(x, y);
                input[0][y][x][0] = (Color.red(pixel) / 255.0f - 0.5f) / 0.5f;
                input[0][y][x][1] = (Color.green(pixel) / 255.0f - 0.5f) / 0.5f;
                input[0][y][x][2] = (Color.blue(pixel) / 255.0f - 0.5f) / 0.5f;
            }
        }

        // Run inference
        tflite.run(input, output);

        // Extract center pixel depth value (normalized)
        float depthValue = output[0][inputHeight / 2][inputWidth / 2][0];
        return depthValue;
    }

    public void close() {
        tflite.close();
    }
  }
