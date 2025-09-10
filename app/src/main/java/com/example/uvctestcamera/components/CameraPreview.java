package com.example.uvctestcamera.components;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import android.hardware.usb.UsbDevice;
import android.os.*;
import android.util.*;
import android.util.Pair;
import android.view.*;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uvctestcamera.container.facedetection.FaceProcessor;
import com.example.uvctestcamera.container.facedetection.Faces;
import com.example.uvctestcamera.container.mqtt.MQTT;
import com.example.uvctestcamera.databinding.CameraPreviewLayoutBinding;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import com.serenegiant.usb.*;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;
import com.serenegiant.opencv.ImageProcessor;
import org.tensorflow.lite.Interpreter;
import com.mv.engine.FaceBox;
import com.mv.engine.Live;
import com.example.uvctestcamera.container.antispoof.EngineWrapper;

import java.io.*;
import java.nio.*;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.util.*;
//import ai.onnxruntime.*;


public class CameraPreview extends Fragment {

    private static final String TAG = "AndroidUSBCamera";

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final int OUTPUT_SIZE = 192;
    private static final long ANALYZE_INTERVAL_MS = 500;
    private static final float MATCH_THRESHOLD = 1.0f;
    private static final String DEVICE_ID = "fb4ed650-5583-11f0-96c6-855152e3efab";

    private UVCCameraTextureView cameraView;
    private GraphicOverlay overlayView;
    private USBMonitor usbMonitor;
    private UVCCameraHandlerMultiSurface cameraHandler;
    private ImageProcessor mImageProcessor;
    public static Interpreter Detector;
    public static Interpreter Embedder;
    private FaceDetector faceDetector;
    private float[][] embeddings;
    private int surfaceId = 1;
    private int mImageProcessorSurfaceId;
    private boolean mIsRunning;
    private long lastAnalyzedTime = 0;
    private CameraPreviewLayoutBinding CameraViewBinding;
    private SessionMonitor sessionMonitor;

    private EngineWrapper antiSpoofEngine;
    private boolean antiSpoofReady = false;
    private static final float LIVENESS_THRESHOLD = 0.915f; // tune later
    private static final int LIVENESS_ORIENTATION = 7;      // same convention you used earlier

    public static final HashMap<String, Faces.Recognition> savedFaces = new HashMap<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        CameraViewBinding = CameraPreviewLayoutBinding.inflate(inflater, container, false);
        return CameraViewBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraView = CameraViewBinding.cameraView;
        overlayView = CameraViewBinding.graphicOverlay;
        usbMonitor = new USBMonitor(requireContext(), deviceConnectListener);

        cameraHandler = UVCCameraHandlerMultiSurface.createHandler(requireActivity(), cameraView, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT, 1, 1.0f);

        // Load only RetinaFace detector for face detection
        Detector = loadModel("retinaface.tflite");
        // Embedder = loadModel("mobilenet_v2.tflite"); // Not needed for anti-spoofing only
        // loadModelOnnx("FaceRecognition.onnx");

        //Load AS Model
        Log.d(TAG, "=== Initializing Anti-Spoofing Engine ===");
        Log.d(TAG, "Liveness threshold: " + LIVENESS_THRESHOLD);
        Log.d(TAG, "Liveness orientation: " + LIVENESS_ORIENTATION);
        
        antiSpoofEngine = new EngineWrapper(requireContext().getAssets());
        antiSpoofReady = antiSpoofEngine.isReady();
        if (!antiSpoofReady) {
            Log.e(TAG, "Anti-spoof engine failed to initialize!");
        } else {
            Log.i(TAG, "Anti-spoofing engine initialized successfully");
        }

        //ML kit for Detector API - use instead of ReinaFace
        setupFaceDetector();

        // Insert fake data before loading faces for testing
        MQTT.db_handler.dropUserScheduleTable();
        Log.d(TAG, "Inserting fake data for testing...");
        Context context = requireContext();
        MQTT.db_handler.insertFakeData(context);
        // Update embeddings for users without embeddings after TF Lite is loaded
        if (Embedder != null) {
            MQTT.db_handler.updateMissingEmbeddings();
        }
        //

        // Safe USB monitor registration with enhanced error handling
        registerUSBMonitorSafely();

        MQTT.db_handler.loadFacesfromSQL();
        Log.d(CameraPreview.TAG, "Here the savedFaces" + CameraPreview.savedFaces);

        sessionMonitor = new SessionMonitor();
        sessionMonitor.start();

        Toast.makeText(getContext(), "Camera initialized. Connect USB camera if available.", Toast.LENGTH_LONG).show();
    }

    private void registerUSBMonitorSafely() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "Attempting to register USB Monitor...");

                if (usbMonitor != null) {
                    // Register with comprehensive error handling
                    usbMonitor.register();
                    Log.d(TAG, "USB Monitor registered successfully");
                } else {
                    Log.e(TAG, "USB Monitor is null, cannot register");
                }
            } catch (SecurityException e) {
                Log.w(TAG, "USB Security Exception - continuing without USB camera: " + e.getMessage());
            
            } catch (RuntimeException e) {
                Log.e(TAG, "Runtime error with USB Monitor: " + e.getMessage());
                
            } catch (Exception e) {
                Log.e(TAG, "General error registering USB Monitor: " + e.getMessage(), e);
            }
        }, 1000); // 1 second delay
    }

    private Interpreter loadModel(String modelName) {
        try {
            AssetFileDescriptor fileDescriptor = requireContext()
                    .getAssets()
                    .openFd(modelName);

            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();

            MappedByteBuffer model = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.getStartOffset(),
                    fileDescriptor.getDeclaredLength()
            );

            Interpreter interpreter = new Interpreter(model);
            Log.d(TAG, "Deployed model successfully: " + modelName);

            return interpreter;
        } catch (IOException e) {
            Log.e(TAG, "Error loading model: " + modelName, e);
            return null;
        }
    }

    // Ham load model onnx
//    private OrtSession loadModelOnnx(String assetFileName) {
//        OrtSession session = null;
//        try {
//            AssetFileDescriptor afd = getContext().getAssets().openFd(assetFileName);
//            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
//            FileChannel channel = fis.getChannel();
//            long start = afd.getStartOffset();
//            long length = afd.getDeclaredLength();
//
//            ByteBuffer bb = ByteBuffer.allocateDirect((int) length);
//            channel.position(start);
//            channel.read(bb);
//            bb.flip();
//
//            byte[] modelBytes = new byte[bb.remaining()];
//            bb.get(modelBytes);
//
//            ortEnv = OrtEnvironment.getEnvironment();
//            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
//
//            opts.addNnapi();
//
//            session = ortEnv.createSession(modelBytes, opts);
//
//            Log.d(TAG, "Deployed ONNX model successfully: " + assetFileName);
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error loading ONNX model: " + assetFileName, e);
//        }
//        return session;
//    }

    private void setupFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    // ==== Camera Lifecycle ====
    private void startPreview() {
        cameraView.resetFps();
        cameraHandler.startPreview();
        requireActivity().runOnUiThread(() -> {
            try {
                Surface surface = new Surface(cameraView.getSurfaceTexture());
                surfaceId = surface.hashCode();
                cameraHandler.addSurface(surfaceId, surface, false);
                startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        });
    }

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            Toast.makeText(getContext(), "USB Device Attached", Toast.LENGTH_SHORT).show();
            try {
                usbMonitor.requestPermission(device);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when requesting USB permission", e);
                Toast.makeText(getContext(), "USB Permission Error", Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            try {
                if (!usbMonitor.hasPermission(device)) {
                    Toast.makeText(getContext(), "USB permission not granted", Toast.LENGTH_SHORT).show();
                    return;
                }
                cameraHandler.open(ctrlBlock);
                startPreview();
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception during USB connection", e);
                Toast.makeText(getContext(), "USB Security Error", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Error opening camera", e);
            }
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            if (surfaceId != 0) cameraHandler.removeSurface(surfaceId);
            cameraHandler.close();
        }

        @Override
        public void onDettach(UsbDevice device) {
            Toast.makeText(getContext(), "USB Device Detached", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(UsbDevice device) {
            Toast.makeText(getContext(), "USB Permission Cancelled", Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        usbMonitor.register();
    }

    @Override
    public void onStop() {
        super.onStop();
        stopImageProcessor();
        cameraHandler.close();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        sessionMonitor.stop();
        cameraHandler.release();
        usbMonitor.unregister();
        usbMonitor.destroy();
        CameraViewBinding = null;
        if (antiSpoofEngine != null) antiSpoofEngine.destroy();
    }

    // ==== Face Detection  ====

    private void detectFace(Bitmap bitmap) {
        final Bitmap safeFrame = ensureSoftwareBitmap(bitmap);
        InputImage image = InputImage.fromBitmap(safeFrame, 0);
        faceDetector.process(image)
                .addOnSuccessListener(faces -> onFacesDetected(faces, safeFrame, image))
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

    private void onFacesDetected(List<Face> faces, Bitmap frameBitmap, InputImage inputImage) {
        overlayView.clear();
        if (faces.isEmpty()) return;

        Face face = faces.get(0);
        Rect box = clampRect(face.getBoundingBox(), frameBitmap.getWidth(), frameBitmap.getHeight());
        if (box == null) return;

        float scaleX = overlayView.getWidth() / (float) inputImage.getWidth();
        float scaleY = overlayView.getHeight() / (float) inputImage.getHeight();

        // Anti-spoofing detection
        Pair<String, Faces.Recognition> result = recognize(frameBitmap, box);
        String detectedResult = result.first;
        Faces.Recognition detectedFace = result.second;

        overlayView.draw(box, scaleX, scaleY, detectedResult);

        if ("Real".equals(detectedResult)) {
            String ts = MQTT.getFormattedTimestamp();
            MQTT.sendFaceMatch(detectedFace, ts);
            Toast.makeText(getContext(), "Real Face", Toast.LENGTH_SHORT).show();
        } else if ("Spoof".equals(detectedResult)) {
            Toast.makeText(getContext(), "Spoof Detected", Toast.LENGTH_SHORT).show();
        }
    }

//    private Pair<String, Faces.Recognition> recognize(Bitmap bitmap, Rect boundingBox) {
//        float minDistance = Float.MAX_VALUE;
//        String bestMatch = "Unknown";
//        Faces.Recognition bestFace = null;
//
//        Bitmap cropped = FaceProcessor.cropAndResize(bitmap, boundingBox);
//        ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(cropped);
//
//        embeddings = new float[1][OUTPUT_SIZE];
//        Embedder.runForMultipleInputsOutputs(new Object[]{input}, Collections.singletonMap(0, embeddings));
//
//        for (Map.Entry<String, Faces.Recognition> entry : savedFaces.entrySet()) {
//            float[] known = ((float[][]) entry.getValue().getExtra())[0];
//            float dist = 0f;
//            for (int i = 0; i < OUTPUT_SIZE; i++) dist += Math.pow(embeddings[0][i] - known[i], 2);
//            dist = (float) Math.sqrt(dist);
//
//            if (dist < minDistance) {
//                minDistance = dist;
//                bestMatch = entry.getKey();
//                bestFace = entry.getValue();
//            }
//        }
//
//        if (minDistance > MATCH_THRESHOLD) bestMatch = "Unknown";
//        if (bestFace != null) bestFace.setDistance(minDistance);
//        return new Pair<>(bestMatch, bestFace);
//    }

    private Pair<String, Faces.Recognition> recognize(Bitmap bitmap, Rect boundingBox) {
        // Only run anti-spoofing detection
        if (antiSpoofReady) {
            Log.d(TAG, "=== Starting Anti-Spoofing Analysis ===");
            
            // Convert the whole frame to NV21 once (uses the same bitmap you pass to ML Kit)
            byte[] nv21 = argb8888ToNV21(bitmap);
            Log.d(TAG, "Frame converted to NV21, size: " + nv21.length + " bytes");

            // Clamp box just in case (you already do this outside)
            Rect box = clampRect(boundingBox, bitmap.getWidth(), bitmap.getHeight());
            if (box != null) {
                Log.d(TAG, "Face bounding box: [" + box.left + ", " + box.top + ", " + box.right + ", " + box.bottom + "]");
                Log.d(TAG, "Face size: " + (box.right - box.left) + "x" + (box.bottom - box.top) + " pixels");
                
                FaceBox fb = new FaceBox(box.left, box.top, box.right, box.bottom, 0f);
                Log.d(TAG, "Calling livenessScore with orientation: " + LIVENESS_ORIENTATION);
                
                float score = antiSpoofEngine.livenessScore(
                        nv21,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        LIVENESS_ORIENTATION,
                        fb
                );
                
                Log.d(TAG, "=== Anti-Spoofing Result ===");
                Log.d(TAG, "Liveness score: " + score);
                Log.d(TAG, "Threshold: " + LIVENESS_THRESHOLD);
                Log.d(TAG, "Decision: " + (score < LIVENESS_THRESHOLD ? "SPOOF" : "REAL"));

                if (score < LIVENESS_THRESHOLD) {
                    // Detected as spoof
                    Log.w(TAG, "SPOOF DETECTED - Score (" + score + ") < Threshold (" + LIVENESS_THRESHOLD + ")");
                    Faces.Recognition spoof = new Faces.Recognition("Spoof", "Spoof", 0.0f);
                    spoof.setDistance(Float.NaN);
                    return new Pair<>("Spoof", spoof);
                } else {
                    // Detected as real face
                    Log.i(TAG, "REAL FACE - Score (" + score + ") >= Threshold (" + LIVENESS_THRESHOLD + ")");
                    Faces.Recognition real = new Faces.Recognition("Real", "Real", score);
                    real.setDistance(0.0f);
                    return new Pair<>("Real", real);
                }
            } else {
                Log.e(TAG, "Face bounding box is null or invalid");
            }
        } else {
            Log.w(TAG, "Anti-spoofing engine not ready - returning Unknown");
        }

        // Anti-spoofing not ready, return unknown
        Faces.Recognition unknown = new Faces.Recognition("Unknown", "Unknown", 0.0f);
        unknown.setDistance(Float.NaN);
        return new Pair<>("Unknown", unknown);
    }

    // ==== Frame Processing ====
    protected void startImageProcessor(int width, int height) {
        mIsRunning = true;
        if (mImageProcessor == null) {
            mImageProcessor = new ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT, new FrameProcessorCallback(width, height));
            mImageProcessor.start(width, height);
            Surface surface = mImageProcessor.getSurface();
            mImageProcessorSurfaceId = surface != null ? surface.hashCode() : 0;
            if (mImageProcessorSurfaceId != 0) cameraHandler.addSurface(mImageProcessorSurfaceId, surface, false);
        }
    }

    protected void stopImageProcessor() {
        if (mImageProcessorSurfaceId != 0) cameraHandler.removeSurface(mImageProcessorSurfaceId);
        if (mImageProcessor != null) mImageProcessor.release();
    }

    protected class FrameProcessorCallback implements ImageProcessor.ImageProcessorCallback {
        private final int width, height;
        private Bitmap mFrame;

        protected FrameProcessorCallback(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public void onFrame(ByteBuffer frame) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAnalyzedTime < ANALYZE_INTERVAL_MS) return;
            lastAnalyzedTime = currentTime;

            if (mFrame == null) mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                frame.rewind();
                mFrame.copyPixelsFromBuffer(frame);
                detectFace(Bitmap.createBitmap(mFrame));
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }

        @Override
        public void onResult(int type, float[] result) {
        }
    }

    private class SessionMonitor {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable task = this::run;

        void start() {
            handler.post(task);
        }

        void stop() {
            handler.removeCallbacksAndMessages(null);
        }

        private void run() {
            long now = System.currentTimeMillis();
            long nextEndMillis = MQTT.db_handler.getNextSessionEndTimeMillis(DEVICE_ID);
            long delay = Math.max(0, Math.min(nextEndMillis - now, 24 * 60 * 60 * 1000));
            MQTT.db_handler.loadFacesfromSQL();
            handler.postDelayed(task, delay > 0 ? delay : 5 * 60 * 1000);
        }

    }

    private static @Nullable Bitmap ensureSoftwareBitmap(@Nullable Bitmap src) {
        if (src == null) return null;
        if (src.isRecycled()) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (src.getConfig() == Bitmap.Config.HARDWARE) {
                // Convert to a software-backed bitmap before any Canvas operations
                return src.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        return src;
    }

    private static @Nullable Rect clampRect(@NonNull Rect r, int w, int h) {
        int left = Math.max(0, r.left);
        int top = Math.max(0, r.top);
        int right = Math.min(w, r.right);
        int bottom = Math.min(h, r.bottom);
        if (right <= left || bottom <= top) return null;  // fully out-of-bounds
        return new Rect(left, top, right, bottom);
    }

    private static byte[] argb8888ToNV21(Bitmap src) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        int[] argb = new int[width * height];
        src.getPixels(argb, 0, width, 0, 0, width, height);

        byte[] yuv = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[j * width + i];

                int R = (c >> 16) & 0xff;
                int G = (c >> 8) & 0xff;
                int B = c & 0xff;

                // BT.601 full range
                int Y = (( 66 * R + 129 * G +  25 * B + 128) >> 8) + 16;
                int U = ((-38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
                int V = ((112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

                Y = Math.max(0, Math.min(255, Y));
                U = Math.max(0, Math.min(255, U));
                V = Math.max(0, Math.min(255, V));

                yuv[yIndex++] = (byte) Y;

                // NV21: VU interleaved, write on even rows/cols
                if ((j & 1) == 0 && (i & 1) == 0) {
                    yuv[uvIndex++] = (byte) V;
                    yuv[uvIndex++] = (byte) U;
                }
            }
        }
        return yuv;
    }
}
