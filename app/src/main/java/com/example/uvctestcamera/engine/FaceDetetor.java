//package com.example.uvctestcamera.engine;
//
//import android.content.res.AssetManager;
//import android.graphics.Bitmap;
//
//import androidx.annotation.Keep;
//
//import java.util.List;
//
//public class FaceDetector extends Component {
//
//    @Keep
//    private long nativeHandler;
//
//    public FaceDetector() {
//        nativeHandler = createInstance();
//    }
//
//    @Override
//    public long createInstance() {
//        return allocate();
//    }
//
//    /** Loads detection model(s) from assets via JNI. Return 0 on success (convention). */
//    public int loadModel(AssetManager assetManager) {
//        return nativeLoadModel(assetManager);
//    }
//
//    /** Detect faces from a Bitmap (must be ARGB_8888). */
//    public List<FaceBox> detect(Bitmap bitmap) {
//        if (bitmap == null || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
//            throw new IllegalArgumentException("Invalid bitmap config value (require ARGB_8888)");
//        }
//        return nativeDetectBitmap(bitmap);
//    }
//
//    /**
//     * Detect faces from YUV420 (NV21) byte array.
//     * @param yuv         NV21 data
//     * @param previewWidth  frame width
//     * @param previewHeight frame height
//     * @param orientation   device/camera orientation degrees (0/90/180/270)
//     */
//    public List<FaceBox> detect(byte[] yuv, int previewWidth, int previewHeight, int orientation) {
//        if (yuv == null || previewWidth * previewHeight * 3 / 2 != yuv.length) {
//            throw new IllegalArgumentException("Invalid yuv data");
//        }
//        return nativeDetectYuv(yuv, previewWidth, previewHeight, orientation);
//    }
//
//    @Override
//    public void destroy() {
//        deallocate();
//    }
//
//    //////////////////////////////// Native ////////////////////////////////////
//    @Keep
//    private native long allocate();
//
//    @Keep
//    private native void deallocate();
//
//    @Keep
//    private native int nativeLoadModel(AssetManager assetManager);
//
//    @Keep
//    private native List<FaceBox> nativeDetectBitmap(Bitmap bitmap);
//
//    @Keep
//    private native List<FaceBox> nativeDetectYuv(
//            byte[] yuv,
//            int previewWidth,
//            int previewHeight,
//            int orientation
//    );
//}
//
