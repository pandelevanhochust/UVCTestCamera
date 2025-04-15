package com.example.uvctestcamera.UIComponents;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.os.Build;
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
import com.example.uvctestcamera.ImageProcessor;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.widget.UVCCameraTextureView;
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

public class CameraPreview extends Fragment {

    private UVCCameraTextureView cameraView;
    private Button captureBtn;
    private USBMonitor usbMonitor;
    private UVCCameraHandlerMultiSurface cameraHandler;
    private int surfaceId = 1;

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final boolean USE_SURFACE_ENCODER = false;
    private static final int PREVIEW_MODE = 1;

    private static final String TAG = "AndroidUSBCamera";

    private CameraPreviewLayoutBinding CameraViewBinding;

    private GraphicOverlay overlayView;
    private Interpreter tfLite;
    private float[][] embeddings;
    private final HashMap<String, Faces.Recognition> savedFaces = new HashMap<>();
    private FaceDetector faceDetector;

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 112;

    private volatile boolean mIsRunning;
    private int mImageProcessorSurfaceId;
    protected ImageProcessor mImageProcessor;
    protected SurfaceView mResultView;


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,@Nullable Bundle savedInstanceState) {
        CameraViewBinding = CameraPreviewLayoutBinding.inflate(inflater,container,false);
        return CameraViewBinding.getRoot();
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        cameraView = CameraViewBinding.cameraView;

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
        Log.d(TAG,"Reach onViewCreated");
    }

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener
            = new USBMonitor.OnDeviceConnectListener() {

        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(getContext(), "USB Device Attached", Toast.LENGTH_SHORT).show();
//            if (!usbMonitor.hasPermission(device)) {
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

                cameraView.resetFps();
                cameraHandler.startPreview();

                if(cameraHandler != null) {
                    cameraHandler.open(ctrlBlock);
                    SurfaceTexture st = cameraView.getSurfaceTexture();

                    if (st != null) {
                        Surface surface = new Surface(st);
                        surfaceId = surface.hashCode();
                        cameraHandler.addSurface(surfaceId,surface,false);
                    }

                    startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                }
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
        // 🔍 Log connected USB devices
        for (UsbDevice device : usbMonitor.getDeviceList()) {
            Log.d("USB", "Device: " + device.getVendorId() + ":" + device.getProductId());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cameraHandler.release();
        usbMonitor.unregister();
        usbMonitor.destroy();
        CameraViewBinding = null;
    }

    protected void startImageProcessor(final int processing_width, final int processing_height) {
        if (DEBUG) Log.v(TAG, "startImageProcessor:");
        mIsRunning = true;
        if (mImageProcessor == null) {
            mImageProcessor = new ImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT,	// src size
                    new MyImageProcessorCallback(processing_width, processing_height));	// processing size
            mImageProcessor.start(processing_width, processing_height);	// processing size
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

        protected MyImageProcessorCallback( final int processing_width, final int processing_height) {
            width = processing_width;
            height = processing_height;
        }

        @Override
        public void onFrame(final ByteBuffer frame) {
            if (mResultView != null) {
                final SurfaceHolder holder = mResultView.getHolder();
                if ((holder == null)
                        || (holder.getSurface() == null)
                        || (frame == null)) return;

//--------------------------------------------------------------------------------
// Using SurfaceView and Bitmap to draw resulted images is inefficient way,
// but functions onOpenCV are relatively heavy and expect slower than source
// frame rate. So currently just use the way to simply this sample app.
// If you want to use much efficient way, try to use as same way as
// UVCCamera class use to receive images from UVC camera.
//--------------------------------------------------------------------------------
                if (mFrame == null) {
                    mFrame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    final float scaleX = mResultView.getWidth() / (float)width;
                    final float scaleY = mResultView.getHeight() / (float)height;
                    matrix.reset();
                    matrix.postScale(scaleX, scaleY);
                }
                try {
                    frame.clear();
                    mFrame.copyPixelsFromBuffer(frame);
                    final Canvas canvas = holder.lockCanvas();
                    if (canvas != null) {
                        try {
                            canvas.drawBitmap(mFrame, matrix, null);
                        } catch (final Exception e) {
                            Log.w(TAG, e);
                        } finally {
                            holder.unlockCanvasAndPost(canvas);
                        }
                    }
                } catch (final Exception e) {
                    Log.w(TAG, e);
                }
            }
        }

        @Override
        public void onResult(final int type, final float[] result) {
            // do something
        }

    }


    private void detectFace(Bitmap bitmap) {
        Log.d(TAG,"Reach detect face ");
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        faceDetector.process(image)
                .addOnSuccessListener(faces -> onFacesDetected(faces, image))
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

    private void onFacesDetected(List<Face> faces, InputImage inputImage){
        overlayView.clear();

        if(!faces.isEmpty()){
            Log.d(TAG,"New face detected");
            Face face = faces.get(0);
            Rect boundingBox = face.getBoundingBox();
            overlayView.draw(boundingBox,1.0f,1.0f,"Detected Person");

            Pair <String,Float> output = recognize(inputImage.getBitmapInternal(),boundingBox);

            if (output.second >= 1.00f){
                overlayView.draw(boundingBox, 1.0f, 1.0f, "unknown");
            }
            if(output.second < 1.00f) {
                overlayView.draw(boundingBox, 1.0f, 1.0f, output.first);
            }
            // need to add detection here
        }else{
            overlayView.draw(null,1.0f,1.0f,"unknown");
        }
    }

    // Using TFLite to recognize
    private Pair<String, Float> recognize(Bitmap bitmap, Rect boundingBox) {
        float minDistance = Float.MAX_VALUE;
        String name = null;

        Bitmap cropped = FaceProcessor.cropAndResize(bitmap, boundingBox);
        ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(cropped);

        Object[] inputArray = {input};
        Map<Integer, Object> outputMap = new HashMap<>();
        embeddings = new float[1][OUTPUT_SIZE];
        outputMap.put(0, embeddings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        for (Map.Entry<String, Faces.Recognition> entry : savedFaces.entrySet()) {
            float[] knownEmb = ((float[][]) entry.getValue().getExtra())[0];
            float distance = 0;
            for (int i = 0; i < embeddings[0].length; i++) {
                float diff = embeddings[0][i] - knownEmb[i];
                distance += diff * diff;
            }
            if (distance < minDistance) {
                minDistance = distance;
                name = entry.getKey();
            }
        }

        return new Pair<>(name, minDistance);
    }

    private void train(Image image){}


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
    public void loadFaces(){}


    //    private void startPreview() {
//        if (DEBUG) Log.v(TAG, "startPreview:");
//        cameraView.resetFps();
//        cameraHandler.startPreview();
//        runOnUiThread(new Runnable() {
//            @Override
//            public void run() {
//                try {
//                    final SurfaceTexture st = cameraView.getSurfaceTexture();
//                    if (st != null) {
//                        final Surface surface = new Surface(st);
//                        mPreviewSurfaceId = surface.hashCode();
//                        cameraHandler.addSurface(mPreviewSurfaceId, surface, false);
//                    }
////                    mCaptureButton.setVisibility(View.VISIBLE);
////                    startImageProcessor(PREVIEW_WIDTH, PREVIEW_HEIGHT);
//                } catch (final Exception e) {
//                    Log.w(TAG, e);
//                }
//            }
//        });
////        updateItems();
//    }
//
//    private void stopPreview() {
//        if (DEBUG) Log.v(TAG, "stopPreview:");
////        stopImageProcessor();
//        if (mPreviewSurfaceId != 0) {
//            cameraHandler.removeSurface(mPreviewSurfaceId);
//            mPreviewSurfaceId = 0;
//        }
//        cameraHandler.close();
////        setCameraButton(false);
//    }

}
