package com.example.uvctestcamera.container.facedetection;

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
//

    // For RetinaFace model - different input size and format
    public static ByteBuffer convertBitmapToByteBufferRetinaFace(Bitmap bitmap) {
        final int inputSize = 640; // RetinaFace typically uses 640x640
        
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("Bitmap is null or recycled");
        }

        // 1) Scale to RetinaFace input size
        Bitmap scaled = (bitmap.getWidth() == inputSize && bitmap.getHeight() == inputSize)
                ? bitmap
                : Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);

        // 2) Ensure ARGB_8888
        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        }

        // 3) Read pixels
        int[] intValues = new int[inputSize * inputSize];
        scaled.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);

        // 4) Convert to float RGB (RetinaFace expects different normalization)
        ByteBuffer bb = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder());
        
        for (int p : intValues) {
            // RetinaFace typically expects values in range [0, 255] or normalized differently
            float r = ((p >> 16) & 0xFF);  // No normalization, keep 0-255 range
            float g = ((p >> 8)  & 0xFF);
            float b = ( p        & 0xFF);
            bb.putFloat(r);
            bb.putFloat(g);
            bb.putFloat(b);
        }
        bb.rewind();
        return bb;
    }

    public static ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        final int inputSize = 640;

        if (bitmap == null || bitmap.isRecycled()) {
            throw new IllegalArgumentException("Bitmap is null or recycled");
        }

        // 1) Scale to model input
        Bitmap scaled = (bitmap.getWidth() == inputSize && bitmap.getHeight() == inputSize)
                ? bitmap
                : Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);

        // 2) Ensure ARGB_8888 so pixel access is stable
        if (scaled.getConfig() != Bitmap.Config.ARGB_8888) {
            scaled = scaled.copy(Bitmap.Config.ARGB_8888, false);
        }

        // 3) Read exactly inputSize*inputSize pixels with stride = inputSize
        int[] intValues = new int[inputSize * inputSize];
        scaled.getPixels(intValues, /*offset=*/0, /*stride=*/inputSize,
                /*x=*/0, /*y=*/0, /*width=*/inputSize, /*height=*/inputSize);

        // 4) Convert to float RGB in NHWC order (0..1). Change if your model expects CHW or BGR.
        ByteBuffer bb = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
                .order(ByteOrder.nativeOrder());
        for (int p : intValues) {
            float r = ((p >> 16) & 0xFF) / 255f;
            float g = ((p >> 8)  & 0xFF) / 255f;
            float b = ( p        & 0xFF) / 255f;
            bb.putFloat(r);
            bb.putFloat(g);
            bb.putFloat(b);
        }
        bb.rewind();
        return bb;
    }
    // This is used in the android device
    public static ByteBuffer convertBitmapToByteBufferinDatabase(Bitmap bitmap) {
        int inputSize = 640;

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);

        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[inputSize * inputSize];
        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);

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
        float scaleWidth = ((float) 640) / width;
        float scaleHeight = ((float) 640) / height;
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
