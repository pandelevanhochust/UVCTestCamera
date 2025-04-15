import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Rect;
import android.media.Image;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.*;

import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.*;

import com.example.attendancesystem.FaceProcessor;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraFragment;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.utils.*;
import com.jiangdg.ausbc.widget.*;
import com.jiangdg.ausbc.*;
import com.jiangdg.ausbc.base.*;
import com.example.attendancesystem.databinding.CameraPreviewLayoutBinding;

// SetUp Camera
public class CameraPreview extends CameraFragment  {
    private static final String TAG = "AndroidUSBCamera";

    private CameraPreviewLayoutBinding CameraViewBinding;

    private GraphicOverlay overlayView;
    private Interpreter tfLite;
    private float[][] embeddings;
    private final HashMap<String, Faces.Recognition> savedFaces = new HashMap<>();

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 112;


    @Nullable
    @Override
    public View getRootView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        if (CameraViewBinding == null) {
            CameraViewBinding = CameraPreviewLayoutBinding.inflate(inflater, container, false);
            overlayView = CameraViewBinding.graphicOverlay;
            loadModel();
//            loadFaces();
        }
        return CameraViewBinding.getRoot();
    }

    @Nullable
    @Override
    // Camera Holder here
    public IAspectRatio getCameraView() {
        Log.d(TAG, "Camera Showing UPPPPPPPP");
        return CameraViewBinding.previewView;
    }

    @Nullable
    @Override
    // Layout of camera_preview_layout
    public ViewGroup getCameraViewContainer() {
        Log.d(TAG, "Hell Ye");
        return CameraViewBinding.getRoot();
    }

    @Override
    public void onCameraState(@NonNull MultiCameraClient.ICamera self,
                              @NonNull ICameraStateCallBack.State code,
                              @Nullable String msg) {
        switch (code) {
            case OPENED:
                handleCameraOpened();
                break;
            case CLOSED:
                handleCameraClosed();
                break;
            case ERROR:
                handleCameraError(msg);
                break;
        }
    }

    @Override
    public int getGravity() {
        return Gravity.TOP;
    }

    private void handleCameraOpened() {
        Log.d(TAG, "Camera opened");
    }

    private void handleCameraClosed() {
        Log.d(TAG, "Camera closed");
    }

    private void handleCameraError(@Nullable String msg) {
        Log.e(TAG, "Camera error: " + msg);
    }

    //analyze Image
    /*
    USB Camera Frame (NV21)
    → convert to Bitmap
     → wrap in InputImage*/

    @Override
    public void onPreviewFrame(byte[] data, int width, int height, int format) {
        // convert nv21 to Bitmap
        Bitmap bitmap = FaceProcessor.nv21ToBitmap(data, width, height);
        if (bitmap == null) return;
        //convert Bitmap to inputImage
        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        // Set up MLKit face detection
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build();

        FaceDetector detector = FaceDetection.getClient(options);

        detector.process(inputImage)
                .addOnSuccessListener(faces -> onFacesDetected(faces, inputImage))
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

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
        } catch (IOException e) {
            Log.e(TAG, "Error loading model", e);
        }
    }

    //Load savedFaces from Backend
    public void loadFaces(){}

}