package com.example.uvctestcamera.components;

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
import com.serenegiant.usb.IFrameCallback;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.util.*;


public class CameraPreview extends Fragment  {

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
    public static Interpreter tfLite;
    private FaceDetector faceDetector;
    private float[][] embeddings;
    private int surfaceId = 1;
    private int mImageProcessorSurfaceId;
    private boolean mIsRunning;
    private long lastAnalyzedTime = 0;
    private CameraPreviewLayoutBinding CameraViewBinding;
    private SessionMonitor sessionMonitor;

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

        loadModel();
        setupFaceDetector();
        usbMonitor.register();
        MQTT.db_handler.loadFacesfromSQL();

        sessionMonitor = new SessionMonitor();
        sessionMonitor.start();
        Log.d(CameraPreview.TAG, "Here the savedFaces" + CameraPreview.savedFaces);
    }

    private void loadModel() {
        try {
            AssetFileDescriptor fileDescriptor = requireContext().getAssets().openFd("mobile_face_net.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            MappedByteBuffer model = fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.getStartOffset(), fileDescriptor.getDeclaredLength());
            tfLite = new Interpreter(model);
            Log.d(TAG, "Deployed model successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error loading model", e);
        }
    }

    private void setupFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        faceDetector = FaceDetection.getClient(options);
    }

    // ==== [1] Camera Lifecycle ====
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
            //Băt được kết nối USB và xin quyền cho app được truy cập Camera
            usbMonitor.requestPermission(device);
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            if (!usbMonitor.hasPermission(device)) {
                Toast.makeText(getContext(), "USB permission not granted", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                cameraHandler.open(ctrlBlock);
                startPreview();
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
    }

    // ==== Face Detection  ====

    private void detectFace(Bitmap bitmap) {
        final Bitmap safeFrame = ensureSoftwareBitmap(bitmap);
        InputImage image = InputImage.fromBitmap(safeFrame, 0);
        faceDetector.process(image)
                .addOnSuccessListener(faces -> onFacesDetected(faces,safeFrame, image))
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

    private void onFacesDetected(List<Face> faces, Bitmap frameBitmap, InputImage inputImage) {
        overlayView.clear();
        if (faces.isEmpty()) return;

        Face face = faces.get(0);
        Rect box = clampRect(face.getBoundingBox(), frameBitmap.getWidth(), frameBitmap.getHeight());
        if (box == null) return;

        float scaleX = overlayView.getWidth()  / (float) inputImage.getWidth();
        float scaleY = overlayView.getHeight() / (float) inputImage.getHeight();

        // Single recognition path (remove the duplicate call)
        Pair<String, Faces.Recognition> result = recognize(frameBitmap, box);
        String detectedName = result.first;
        Faces.Recognition detectedFace = result.second;

        overlayView.draw(box, scaleX, scaleY, detectedName);

        if (!"Unknown".equals(detectedName)) {
            String ts = MQTT.getFormattedTimestamp();
            MQTT.sendFaceMatch(detectedFace, ts);
            Toast.makeText(getContext(), "Name: " + detectedName, Toast.LENGTH_SHORT).show();
        }
    }

    private Pair<String, Faces.Recognition> recognize(Bitmap bitmap, Rect boundingBox) {
        float minDistance = Float.MAX_VALUE;
        String bestMatch = "Unknown";
        Faces.Recognition bestFace = null;

        Bitmap cropped = FaceProcessor.cropAndResize(bitmap, boundingBox);
        ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(cropped);

        embeddings = new float[1][OUTPUT_SIZE];
        tfLite.runForMultipleInputsOutputs(new Object[]{input}, Collections.singletonMap(0, embeddings));

        for (Map.Entry<String, Faces.Recognition> entry : savedFaces.entrySet()) {
            float[] known = ((float[][]) entry.getValue().getExtra())[0];
            float dist = 0f;
            for (int i = 0; i < OUTPUT_SIZE; i++) dist += Math.pow(embeddings[0][i] - known[i], 2);
            dist = (float) Math.sqrt(dist);

            if (dist < minDistance) {
                minDistance = dist;
                bestMatch = entry.getKey();
                bestFace = entry.getValue();
            }
        }

        if (minDistance > MATCH_THRESHOLD) bestMatch = "Unknown";
        if (bestFace != null) bestFace.setDistance(minDistance);
        return new Pair<>(bestMatch, bestFace);
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


}
