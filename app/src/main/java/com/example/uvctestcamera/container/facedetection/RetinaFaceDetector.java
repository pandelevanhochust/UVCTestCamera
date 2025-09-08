package com.example.uvctestcamera.container.facedetection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class RetinaFaceDetector {
    private static final String TAG = "RetinaFaceDetector";
    private static final String MODEL_FILE = "retinaface.tflite";
    private static final int INPUT_SIZE = 640;
    private static final int OUTPUT_SIZE = 20;
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    private Interpreter interpreter;
    private ByteBuffer inputBuffer;
    private float[][][][] bboxOutput;
    private float[][][][] clsOutput;
    private ExecutorService executorService;

    public interface DetectionCallback {
        void onResult(List<Rect> faces);
        void onError(Exception e);
    }

    public RetinaFaceDetector(Context context) throws IOException {
        Log.d(TAG, "Initializing RetinaFace detector...");

        // Load model
        MappedByteBuffer modelBuffer = loadModelFile(context);

        // Configure interpreter options
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4); // Use 4 threads for better performance

        interpreter = new Interpreter(modelBuffer, options);

        // Log model info
        Log.d(TAG, "Input shape: " + Arrays.toString(interpreter.getInputTensor(0).shape()));
        Log.d(TAG, "Output 0 shape: " + Arrays.toString(interpreter.getOutputTensor(0).shape()));
        Log.d(TAG, "Output 1 shape: " + Arrays.toString(interpreter.getOutputTensor(1).shape()));

        // Initialize input/output buffers
        inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        inputBuffer.order(ByteOrder.nativeOrder());

        bboxOutput = new float[1][OUTPUT_SIZE][OUTPUT_SIZE][4];
        clsOutput = new float[1][OUTPUT_SIZE][OUTPUT_SIZE][2];

        // Initialize thread pool
        executorService = Executors.newSingleThreadExecutor();

        Log.d(TAG, "RetinaFace detector initialized successfully");
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        try {
            return context.getAssets().openFd(MODEL_FILE).createInputStream().getChannel()
                    .map(FileChannel.MapMode.READ_ONLY,
                            context.getAssets().openFd(MODEL_FILE).getStartOffset(),
                            context.getAssets().openFd(MODEL_FILE).getDeclaredLength());
        } catch (IOException e) {
            Log.e(TAG, "Error loading model file: " + MODEL_FILE, e);
            throw e;
        }
    }

    /**
     * Synchronous face detection
     */
    public List<Rect> detectFaces(Bitmap bitmap) {
        long startTime = System.currentTimeMillis();

        try {
            // Preprocess image
            preprocessImage(bitmap);

            // Run inference
            Object[] inputs = {inputBuffer};
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, clsOutput);  // Classification output
            outputs.put(1, bboxOutput); // Bounding box output
            interpreter.runForMultipleInputsOutputs(inputs, outputs);

            // Post-process results
            List<Rect> faces = postprocessResults(bitmap.getWidth(), bitmap.getHeight());

            long inferenceTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, String.format("Inference completed in %dms, detected %d faces",
                    inferenceTime, faces.size()));

            return faces;

        } catch (Exception e) {
            Log.e(TAG, "Error during face detection", e);
            return new ArrayList<>();
        }
    }

    /**
     * Asynchronous face detection
     */
    public void detectFacesAsync(Bitmap bitmap, DetectionCallback callback) {
        executorService.execute(() -> {
            try {
                List<Rect> faces = detectFaces(bitmap);
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(faces));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e));
            }
        });
    }

    private void preprocessImage(Bitmap bitmap) {
        // Resize bitmap to 640x640
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        inputBuffer.rewind();
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        // Convert to float and normalize [0,1]
        for (int pixel : pixels) {
            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
            inputBuffer.putFloat((pixel & 0xFF) / 255.0f);         // B
        }

        if (resized != bitmap) {
            resized.recycle(); // Free memory
        }
    }

    private List<Rect> postprocessResults(int originalWidth, int originalHeight) {
        List<Rect> faces = new ArrayList<>();

        for (int y = 0; y < OUTPUT_SIZE; y++) {
            for (int x = 0; x < OUTPUT_SIZE; x++) {
                // Get confidence score for face class
                float confidence = clsOutput[0][y][x][1]; // Index 1 is face class

                if (confidence > CONFIDENCE_THRESHOLD) {
                    // Get bbox coordinates (normalized)
                    float centerX = bboxOutput[0][y][x][0];
                    float centerY = bboxOutput[0][y][x][1];
                    float width = bboxOutput[0][y][x][2];
                    float height = bboxOutput[0][y][x][3];

                    // Convert to image coordinates
                    int scale = INPUT_SIZE / OUTPUT_SIZE; // 32
                    float imgCenterX = (x * scale + centerX) / INPUT_SIZE;
                    float imgCenterY = (y * scale + centerY) / INPUT_SIZE;
                    float imgWidth = width / INPUT_SIZE;
                    float imgHeight = height / INPUT_SIZE;

                    // Scale to original image size
                    int finalX = (int)((imgCenterX - imgWidth/2) * originalWidth);
                    int finalY = (int)((imgCenterY - imgHeight/2) * originalHeight);
                    int finalW = (int)(imgWidth * originalWidth);
                    int finalH = (int)(imgHeight * originalHeight);

                    // Clamp to image bounds
                    Rect face = new Rect(
                            Math.max(0, finalX),
                            Math.max(0, finalY),
                            Math.min(originalWidth, finalX + finalW),
                            Math.min(originalHeight, finalY + finalH)
                    );

                    // Only add valid rectangles
                    if (face.width() > 10 && face.height() > 10) {
                        faces.add(face);
                        Log.v(TAG, String.format("Face detected: confidence=%.3f, rect=[%d,%d,%d,%d]",
                                confidence, face.left, face.top, face.right, face.bottom));
                    }
                }
            }
        }

        return faces;
    }

    public void close() {
        Log.d(TAG, "Closing RetinaFace detector...");

        if (executorService != null) {
            executorService.shutdown();
        }

        if (interpreter != null) {
            interpreter.close();
        }
    }
}