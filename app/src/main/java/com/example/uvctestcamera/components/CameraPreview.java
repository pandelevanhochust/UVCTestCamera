package com.example.uvctestcamera.components;

import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;

import android.view.*;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.uvctestcamera.container.facedetection.FaceProcessor;
import com.example.uvctestcamera.container.facedetection.Faces;
import com.example.uvctestcamera.container.facedetection.RetinaFaceDetector;
import com.example.uvctestcamera.container.mqtt.MQTT;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;
import com.serenegiant.opencv.ImageProcessor;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.uvctestcamera.databinding.CameraPreviewLayoutBinding;
import static com.serenegiant.uvccamera.BuildConfig.DEBUG;

public class CameraPreview extends Fragment implements  IFrameCallback {

    private UVCCameraTextureView cameraView;
    private USBMonitor usbMonitor;
    private UVCCameraHandlerMultiSurface cameraHandler;
    private int surfaceId = 1;

    //  NAVIS Screen size
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final boolean USE_SURFACE_ENCODER = false;
    private static final int PREVIEW_MODE = 1;

    private static final String TAG = "AndroidUSBCamera";
    private CameraPreviewLayoutBinding CameraViewBinding;

    GraphicOverlay overlayView;
    public static Interpreter tfLite;
    private float[][] embeddings;
    public static HashMap<String, Faces.Recognition> savedFaces = new HashMap<>();
    private RetinaFaceDetector retinaFaceDetector;

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 192;

    private volatile boolean mIsRunning;
    private int mImageProcessorSurfaceId;
    protected ImageProcessor mImageProcessor;
    protected SurfaceView mResultView;

    private long lastAnalyzedTime = 0;
    private static final long ANALYZE_INTERVAL_MS = 500;

    private static final float MATCH_THRESHOLD = 1.0f;
    private static final String DEVICE_ID = "fb4ed650-5583-11f0-96c6-855152e3efab";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,@Nullable Bundle savedInstanceState) {
        CameraViewBinding = CameraPreviewLayoutBinding.inflate(inflater,container,false);
        return CameraViewBinding.getRoot();
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        cameraView = CameraViewBinding.cameraView;
        overlayView = CameraViewBinding.graphicOverlay;

        cameraHandler = UVCCameraHandlerMultiSurface.createHandler(requireActivity(),
                cameraView,
                0,
                PREVIEW_WIDTH,
                PREVIEW_HEIGHT,
                1,
                1.0f);

        usbMonitor = new USBMonitor(requireContext(),deviceConnectListener);
        loadModel();
        setupRetinaFaceDetector();
        usbMonitor.register();
        MQTT.db_handler.loadFacesfromSQL();

        Handler sessionMonitorHandler = new Handler(Looper.getMainLooper());
        Runnable[] sessionMonitorRunnable = new Runnable[1];

        sessionMonitorRunnable[0] = () -> {
            long now = System.currentTimeMillis();
            long nextEndMillis = MQTT.db_handler.getNextSessionEndTimeMillis(DEVICE_ID);

            if (nextEndMillis > now) {
                long delay = nextEndMillis - now;

                if (delay <= 0 || delay > 24 * 60 * 60 * 1000) {
                    Log.w(TAG, "⚠️ Delay abnormal, fallback to 5 min");
                    delay = 5 * 60 * 1000;
                }

                Log.d(TAG, "⏰ Scheduling next face reload after session ends in " + delay / 1000 + "s");

                sessionMonitorHandler.postDelayed(() -> {
                    Log.d(TAG, "🔄 Reloading faces after session ended");
                    MQTT.db_handler.loadFacesfromSQL();
                    sessionMonitorHandler.post(sessionMonitorRunnable[0]);
                }, delay);
            } else {
                Log.d(TAG, "🕒 No session found. Forcing reload and checking again in 5 minutes.");
                MQTT.db_handler.loadFacesfromSQL();
                sessionMonitorHandler.postDelayed(sessionMonitorRunnable[0], 5 * 60 * 1000);
            }
            Log.d(TAG,savedFaces.toString());
            Log.d(TAG,"Here the savedFaces" + savedFaces);
        };

        sessionMonitorHandler.post(sessionMonitorRunnable[0]);

        Log.d(TAG,"Here the savedFaces" + savedFaces);
        Log.d(TAG,"Reach onViewCreated");
    }

    private void startPreview() {
        if (DEBUG) Log.v(TAG, "startPreview:");

        cameraView.resetFps();
        cameraHandler.startPreview();

        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final SurfaceTexture st = cameraView.getSurfaceTexture();
                    if (st != null) {
                        final Surface surface = new Surface(st);
                        surfaceId = surface.hashCode();
                        cameraHandler.addSurface(surfaceId, surface, false);
                    }
                    Log.e(TAG, "Reach Start Preview");
                    startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                } catch (final Exception e) {
                    Log.w(TAG, e);
                }
            }
        });
    }

    //Connect device with UVC Camera
    private final USBMonitor.OnDeviceConnectListener deviceConnectListener
            = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(getContext(), "USB Device Attached", Toast.LENGTH_SHORT).show();
            usbMonitor.requestPermission(device);
            Log.d("USB", "onAttach: " + device.getVendorId() + ":" + device.getProductId());
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            if (!usbMonitor.hasPermission(device)) {
                Toast.makeText(getContext(), "USB permission not granted", Toast.LENGTH_SHORT).show();
                return;
            }
            if (DEBUG) Log.v(TAG, "onConnect:");
            try {
                cameraHandler.open(ctrlBlock);
                startPreview();

            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException: no permission to access USB", e);
            } catch (Exception e) {
                Log.e(TAG, "Error opening camera", e);
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            if(surfaceId != 0){
                cameraHandler.removeSurface(surfaceId);
                surfaceId = 0;
            }
            cameraHandler.close();
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(getContext(), "USB Device Detached", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            Toast.makeText(getContext(), "USB Permission Cancelled", Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        usbMonitor.register();
        for (UsbDevice device : usbMonitor.getDeviceList()) {
            Log.d("USB", "Device: " + device.getVendorId() + ":" + device.getProductId());
        }
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
        cameraHandler.release();
        usbMonitor.unregister();
        usbMonitor.destroy();

        // Clean up RetinaFace detector
        if (retinaFaceDetector != null) {
            retinaFaceDetector.close();
            retinaFaceDetector = null;
        }

        CameraViewBinding = null;
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onFrame(ByteBuffer frame){
        Bitmap bitmap = FaceProcessor.ByteBufferToBitmap(frame);
        detectFace(bitmap);
    }

    private void detectFace(Bitmap bitmap) {
//        Log.d(TAG,"Reach detect face ");
        if (retinaFaceDetector != null) {
            retinaFaceDetector.detectFacesAsync(bitmap, new RetinaFaceDetector.DetectionCallback() {
                @Override
                public void onResult(List<Rect> faces) {
                    Log.d(TAG, "Face detected: " + faces.size());
                    onFacesDetected(faces, bitmap);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "Face detection failed", e);
                }
            });
        }
    }

    private void onFacesDetected(List<Rect> faces, Bitmap bitmap){
        overlayView.clear();
        if (!faces.isEmpty()) {
            String detectedName = "Unknown";
            Rect boundingBox = faces.get(0); // Get first detected face
            Faces.Recognition detectedFace = null;

            Log.d(TAG,"Bounding box" + boundingBox);

            float scaleX = overlayView.getWidth() * 1.0f / bitmap.getWidth();
            float scaleY = overlayView.getHeight() * 1.0f / bitmap.getHeight();

            Pair<String, Faces.Recognition> output = recognize(bitmap, boundingBox);
            detectedName = output.first;
            detectedFace = output.second;

            overlayView.draw(boundingBox,scaleX,scaleY,detectedName);
            String timestamp = MQTT.getFormattedTimestamp();

            if(!Objects.equals(detectedName, "Unknown")){
                MQTT.sendFaceMatch(detectedFace,timestamp);
            }
            Log.d(TAG, "Face recognized: " +  detectedName + ", timestamp: " + timestamp);
        }
    }

    // Using TFLite to recognize
    private Pair<String, Faces.Recognition> recognize(Bitmap bitmap, Rect boundingBox) {
        float minDistance = Float.MAX_VALUE;
        String bestMatch = "Unknown";
        Faces.Recognition bestFace = null;

        Bitmap cropped = FaceProcessor.cropAndResize(bitmap, boundingBox);
        ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(cropped);

        Object[] inputArray = {input};
        Map<Integer, Object> outputMap = new HashMap<>();
        embeddings = new float[1][OUTPUT_SIZE];
        outputMap.put(0, embeddings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        if (CameraPreview.savedFaces == null || CameraPreview.savedFaces.isEmpty()) {
            Log.w("Recognition", "No faces saved in memory");
            return new Pair<>("Unknown", null);
        }

        for (Map.Entry<String, Faces.Recognition> entry : CameraPreview.savedFaces.entrySet()) {
            float[] known = ((float[][]) entry.getValue().getExtra())[0];
            float dist = 0f;

            for (int i = 0; i < OUTPUT_SIZE; i++) {
                float diff = embeddings[0][i] - known[i];
                dist += diff * diff;
            }
            dist = (float) Math.sqrt(dist);

            Log.d(TAG, "Distance to " + entry.getKey() + ": " + dist);

            if (dist < minDistance) {
                bestMatch = entry.getKey();
                minDistance = dist;
                bestFace = entry.getValue();
                bestFace.setDistance(dist);
            }

            bestFace.setDistance(minDistance);
        }

        if (minDistance > MATCH_THRESHOLD) {
            bestMatch = "Unknown";
        }
        Log.d("Recognition", "Closest match: " + bestMatch + " with distance: " + minDistance);
        return new Pair<>(bestMatch,bestFace);
    }

    //Loading model
    private void loadModel() {
        try {
            AssetFileDescriptor fileDescriptor = getContext().getAssets().openFd("mobile_face_net.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();

            MappedByteBuffer model = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
            tfLite = new Interpreter(model);
            Log.d(TAG,"Deployed model successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error loading model", e);
        }
    }

    private void setupRetinaFaceDetector() {
        try {
            retinaFaceDetector = new RetinaFaceDetector(requireContext());
            Log.d(TAG,"RetinaFace detector deployed successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error initializing RetinaFace detector", e);
        }
    }

    //Load savedFaces from Backend

    //Analyze frames
    protected void startImageProcessor(final int processing_width, final int processing_height) {
        if (DEBUG) Log.v(TAG, "startImageProcessor:");
        mIsRunning = true;
        if (mImageProcessor == null) {
            mImageProcessor = new ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT, // NAVIS ScreenSize
                    new MyImageProcessorCallback(processing_width, processing_height));  // callback function
            mImageProcessor.start(processing_width, processing_height);  // processing frame
            final Surface surface = mImageProcessor.getSurface();
            mImageProcessorSurfaceId = surface != null ? surface.hashCode() : 0;
            if (mImageProcessorSurfaceId != 0) {
                cameraHandler.addSurface(mImageProcessorSurfaceId, surface, false);
            }
        }
    }

    protected class MyImageProcessorCallback implements ImageProcessor.ImageProcessorCallback {
        private final int width, height;
        private final Matrix matrix = new Matrix();
        private Bitmap mFrame;

        protected MyImageProcessorCallback(final int processing_width, final int processing_height) {
            width = processing_width;
            height = processing_height;
        }

        @Override
        public void onFrame(final ByteBuffer frame) {
//            Log.d(TAG,"Reach this Frame");
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastAnalyzedTime < ANALYZE_INTERVAL_MS) {
                return;
            }
            lastAnalyzedTime = currentTime;
            if (mFrame == null) {
                Log.d(TAG,"Null Frame");
                mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            try {
                frame.rewind();
                mFrame.copyPixelsFromBuffer(frame);
                // Log.d(TAG,"Passed to detection");
                detectFace(Bitmap.createBitmap(mFrame)); //Bitmap
            } catch (final Exception e) {
                Log.w(TAG, e);
            }
        }

        @Override
        public void onResult(final int type, final float[] result) {
        }
    }

    protected void stopImageProcessor() {
        if (DEBUG) Log.v(TAG, "stopImageProcessor:");
        if (mImageProcessorSurfaceId != 0) {
            cameraHandler.removeSurface(mImageProcessorSurfaceId);
            mImageProcessorSurfaceId = 0;
        }
        if (mImageProcessor != null) {
            mImageProcessor.release();
            mImageProcessor = null;
        }
    }

}
