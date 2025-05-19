package com.example.uvctestcamera.UIComponents;

import android.content.res.AssetFileDescriptor;
import android.graphics.*;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import android.view.*;
import android.widget.Button;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.uvctestcamera.FaceProcessor;
import com.example.uvctestcamera.Faces;
import com.example.uvctestcamera.R;
import com.example.uvctestcamera.backend.Database;
import com.example.uvctestcamera.backend.MQTT;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.opencv.ImageProcessor;

import org.json.JSONArray;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.uvctestcamera.databinding.CameraPreviewLayoutBinding;
import static com.serenegiant.uvccamera.BuildConfig.DEBUG;

public class CameraPreview extends Fragment implements  IFrameCallback {

    private UVCCameraTextureView cameraView;
    private USBMonitor usbMonitor;
    private UVCCameraHandlerMultiSurface cameraHandler;
    private int surfaceId = 1;

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
    private FaceDetector faceDetector;

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 192;

    private volatile boolean mIsRunning;
    private int mImageProcessorSurfaceId;
    protected ImageProcessor mImageProcessor;
    protected SurfaceView mResultView;

    private long lastAnalyzedTime = 0;
    private static final long ANALYZE_INTERVAL_MS = 500;

    private static final float MATCH_THRESHOLD = 1.0f;

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
        setupFaceDetector();
        usbMonitor.register();
        MQTT.db_handler.loadFacesfromSQL();
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
        CameraViewBinding = null;
    }

    @Override
    public void onFrame(ByteBuffer frame){
        Bitmap bitmap = FaceProcessor.ByteBufferToBitmap(frame);
        detectFace(bitmap);
    }

    private void detectFace(Bitmap bitmap) {
        Log.d(TAG,"Reach detect face ");
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        faceDetector.process(image)
                .addOnSuccessListener(faces -> {Log.d(TAG, "Face detected");onFacesDetected(faces, image); })
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

    private void onFacesDetected(List<Face> faces, InputImage inputImage){
        overlayView.clear();
        if (!faces.isEmpty()) {
            String detectedName = "Unknown";
            Face face = faces.get(0);
            Rect boundingBox = face.getBoundingBox();
            Log.d(TAG,"Bounding box" + boundingBox);

            float scaleX = overlayView.getWidth() * 1.0f / inputImage.getWidth();
            float scaleY = overlayView.getHeight() * 1.0f / inputImage.getHeight();

            Pair<String, Float> output = recognize(inputImage.getBitmapInternal(), boundingBox);
            detectedName = output.first;

            overlayView.draw(boundingBox,scaleX,scaleY,detectedName);
            String timestamp = String.valueOf(System.currentTimeMillis());
//            MQTT.sendFaceMatch(timestamp,"Unknown");
            Log.d(TAG, "Face recognized: " +  detectedName + ", timestamp: " + timestamp);
        }
    }

    // Using TFLite to recognize
    private Pair<String, Float> recognize(Bitmap bitmap, Rect boundingBox) {
        float minDistance = Float.MAX_VALUE;
        String name = "unknown";

        Bitmap cropped = FaceProcessor.cropAndResize(bitmap, boundingBox);
        ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(cropped);

        Object[] inputArray = {input};
        Map<Integer, Object> outputMap = new HashMap<>();
        embeddings = new float[1][OUTPUT_SIZE];
        outputMap.put(0, embeddings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        if (CameraPreview.savedFaces == null || CameraPreview.savedFaces.isEmpty()) {
            Log.w("Recognition", "No faces saved in memory");
            return new Pair<>("unknown", -1f);
        }

        for (Map.Entry<String, Faces.Recognition> entry : CameraPreview.savedFaces.entrySet()) {
            float[] known = ((float[][]) entry.getValue().getExtra())[0];
//            Log.d(TAG,"Here the face in the savedFaces " + known.length);
            float dist = 0f;
            for (int i = 0; i < OUTPUT_SIZE; i++) {
                float diff = embeddings[0][i] - known[i];
                dist += diff * diff;
            }
            dist = (float) Math.sqrt(dist);
            Log.d(TAG,"Here the distance" + dist);

            if (dist < minDistance) {
                minDistance = dist;
                name = entry.getKey();
            }

            if (minDistance > MATCH_THRESHOLD) {
                name = "Unknown";
            }
        }
        Log.d("Recognition", "Closest match: " + name + " with distance: " + minDistance);
        return new Pair<>(name, minDistance);
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

    private void setupFaceDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        faceDetector = FaceDetection.getClient(options);
        Log.d(TAG,"Face detector deployed successfully");
    }

    //Load savedFaces from Backend

    //Analyze frames
    protected void startImageProcessor(final int processing_width, final int processing_height) {
        if (DEBUG) Log.v(TAG, "startImageProcessor:");
        mIsRunning = true;
        if (mImageProcessor == null) {
            mImageProcessor = new ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT, // src size
                    new MyImageProcessorCallback(processing_width, processing_height));  // processing size
            mImageProcessor.start(processing_width, processing_height);  // processing size
            final Surface surface = mImageProcessor.getSurface();
            mImageProcessorSurfaceId = surface != null ? surface.hashCode() : 0;
            if (mImageProcessorSurfaceId != 0) {
                cameraHandler.addSurface(mImageProcessorSurfaceId, surface, false);
            }
        }
    }

    // Custom Image Processor Callback for processing frames
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
            Log.d(TAG,"Reach this Frame");

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
                Log.d(TAG,"Passed to detection");
                detectFace(Bitmap.createBitmap(mFrame));
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
