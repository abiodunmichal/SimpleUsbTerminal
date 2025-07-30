package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class YuvToRgbConverter {
    public YuvToRgbConverter(Context context) {}

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public void yuvToRgb(Image image, Bitmap output) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();

        Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (decoded != null) {
            android.graphics.Canvas canvas = new android.graphics.Canvas(output);
            canvas.drawBitmap(decoded, 0, 0, null);
        }
    }
    }
