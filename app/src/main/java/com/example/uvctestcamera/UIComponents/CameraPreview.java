package com.example.uvctestcamera.UIComponents;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;

import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.example.uvctestcamera.FaceProcessor;
import com.example.uvctestcamera.Faces;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
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

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 112;

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
        startTracking();
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
                if(cameraHandler != null) {
                    cameraHandler.open(ctrlBlock);
                    SurfaceTexture st = cameraView.getSurfaceTexture();
                    Surface surface = new Surface(st);
                    surfaceId = surface.hashCode();
                    cameraHandler.addSurface(surfaceId,surface,false);
                    cameraHandler.startPreview();
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

//    @Override
//    public void onStop() {
//        stopPreview();
//        usbMonitor.unregister();
//        super.onStop();
//    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cameraHandler.release();
        usbMonitor.unregister();
        usbMonitor.destroy();
        CameraViewBinding = null;
    }


    public void startTracking(){}

    private void onFacesDetected(List<Face> faces, InputImage inputImage){
        overlayView.clear();

        if(!faces.isEmpty()){
            Log.d(TAG,"New face detected");
            Face face = faces.get(0);
            Rect boundingBox = face.getBoundingBox();
            overlayView.draw(boundingBox,1.0f,1.0f,"Detected Person");
//            Image image = inputImage.getMediaImage();
//            int rotation = inputImage.getRotationDegrees();
//            Pair<String,Float> output  = recognize(image, rotation,boundingBox);

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
            Log.e(TAG,"Deployed model successfully");
        } catch (IOException e) {
            Log.e(TAG, "Error loading model", e);
        }
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
