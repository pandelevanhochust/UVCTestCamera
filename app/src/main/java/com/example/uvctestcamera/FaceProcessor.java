package com.example.uvctestcamera;

import android.graphics.*;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

public class FaceProcessor {
    private static int INPUT_SIZE = 112;
    public static Bitmap ImgtoBmp(Image image, int rotation, Rect boundingBox ){
        Bitmap bitmap = ImgtoBitmap(image);
        //rotate
        Bitmap cropped_bitmap = cropBitmap(bitmap,boundingBox);
        Bitmap resized_bitmap = resizeBitmap(cropped_bitmap);

        return resized_bitmap;
    }

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;

    /*
    USB Camera Frame (NV21)
    → convert to Bitmap
    → wrap in InputImage*/
    public static Bitmap nv21ToBitmap(byte[] nv21, int width, int height) {
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    public static Bitmap cropAndResize(Bitmap bitmap, Rect boundingbox){
        Bitmap cropped = cropBitmap(bitmap,boundingbox);
        Bitmap resized_bitmap = resizeBitmap(cropped);
        return resized_bitmap;
    }

    public static Bitmap ByteBufferToBitmap(ByteBuffer frame) {
        Bitmap bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
        frame.rewind();
        bitmap.copyPixelsFromBuffer(frame);
        return resizeBitmap(bitmap);
    }

    //convert to ByteBuffer - this is used only in CameraX
    public static ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        int inputSize = 112;
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        byteBuffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[inputSize * inputSize];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;

        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                final int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f); // R
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);  // G
                byteBuffer.putFloat((val & 0xFF) / 255.0f);         // B
            }
        }
        return byteBuffer;
    }


    //private method
    private static Bitmap ImgtoBitmap(Image image) {

        byte[] nv21=YUV_420_888toNV21(image);


        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private static byte[] YUV_420_888toNV21(Image image) {

        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width*height;
        int uvSize = width*height/4;

        byte[] nv21 = new byte[ySize + uvSize*2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert(image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        }
        else {
            long yBufferPos = -rowStride; // not an actual position
            for (; pos<ySize; pos+=width) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert(rowStride == image.getPlanes()[1].getRowStride());
        assert(pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            try {
                vBuffer.put(1, (byte)~savePixel);
                if (uBuffer.get(0) == (byte)~savePixel) {
                    vBuffer.put(1, savePixel);
                    vBuffer.position(0);
                    uBuffer.position(0);
                    vBuffer.get(nv21, ySize, 1);
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

                    return nv21; // shortcut
                }
            }
            catch (ReadOnlyBufferException ex) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        for (int row=0; row<height/2; row++) {
            for (int col=0; col<width/2; col++) {
                int vuPos = col*pixelStride + row*rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }

        return nv21;
    }

    private static Bitmap cropBitmap(Bitmap image,Rect boundingBox ) {

        float padding = 0.0f;
        RectF cropRectF = new RectF(
                boundingBox.left - padding,
                boundingBox.top - padding,
                boundingBox.right + padding,
                boundingBox.bottom + padding);

        Bitmap resultBitmap = Bitmap.createBitmap((int) cropRectF.width(),
                (int) cropRectF.height(), Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(resultBitmap);

        // draw background
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawRect(//from  w w  w. ja v  a  2s. c  om
                new RectF(0, 0, cropRectF.width(), cropRectF.height()),
                paint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropRectF.left, -cropRectF.top);

        canvas.drawBitmap(image, matrix, paint);

        if (image != null && !image.isRecycled()) {
            image.recycle();
        }

        return resultBitmap;

    }

    private static Bitmap resizeBitmap(Bitmap image){
        int width = image.getWidth();
        int height = image.getHeight();
        float scaleWidth = ((float) 112) / width;
        float scaleHeight = ((float) 112) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                image, 0, 0, width, height, matrix, false);
        image.recycle();
        return resizedBitmap;
    }



}
