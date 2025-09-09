package com.example.uvctestcamera.engine;

import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.Keep;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Live extends Component {

    @Keep
    private long nativeHandler;

    public Live() {
        nativeHandler = createInstance();
    }

    @Override
    public long createInstance() {
        return allocate();
    }

    @Override
    public void destroy() {
        deallocate();
    }

    /**
     * Load liveness model(s) with configs from assets/live/config.json
     * Returns 0 on success, negative on failure (convention).
     */
    public int loadModel(AssetManager assetManager) {
        List<ModelConfig> configs = parseConfig(assetManager);
        if (configs.isEmpty()) {
            Log.e(tag, "parse model config failed");
            return -1;
        }
        return nativeLoadModel(assetManager, configs);
    }

    /**
     * Liveness detection on a face region.
     * @return liveness score (e.g., probability). Thresholding is done on the Java/Kotlin side by you.
     */
    public float detect(byte[] yuv,
                        int previewWidth,
                        int previewHeight,
                        int orientation,
                        FaceBox faceBox) {
        if (yuv == null || previewWidth * previewHeight * 3 / 2 != yuv.length) {
            throw new IllegalArgumentException("Invalid yuv data");
        }
        if (faceBox == null) {
            throw new IllegalArgumentException("faceBox must not be null");
        }
        return nativeDetectYuv(
                yuv,
                previewWidth,
                previewHeight,
                orientation,
                faceBox.left,
                faceBox.top,
                faceBox.right,
                faceBox.bottom
        );
    }

    private List<ModelConfig> parseConfig(AssetManager assetManager) {
        List<ModelConfig> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(assetManager.open("modelconfig.json")))) {

            // Read entire file (Kotlin version read only one line)
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln);
            }

            JSONArray jsonArray = new JSONArray(sb.toString());
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject config = jsonArray.getJSONObject(i);
                ModelConfig mc = new ModelConfig();
                mc.name = config.optString("name");
                mc.width = config.optInt("width");
                mc.height = config.optInt("height");
                mc.scale = (float) config.optDouble("scale");
                mc.shift_x = (float) config.optDouble("shift_x");
                mc.shift_y = (float) config.optDouble("shift_y");
                mc.org_resize = config.optBoolean("org_resize");
                list.add(mc);
            }
        } catch (Exception e) {
            Log.e(tag, "Error parsing live/config.json", e);
        }
        return list;
    }

    public static final String tag = "Live";

    ///////////////////////////////////// Native ////////////////////////////////////
    @Keep
    private native long allocate();

    @Keep
    private native void deallocate();

    @Keep
    private native int nativeLoadModel(AssetManager assetManager, List<ModelConfig> configs);

    @Keep
    private native float nativeDetectYuv(
            byte[] yuv,
            int previewWidth,
            int previewHeight,
            int orientation,
            int left,
            int top,
            int right,
            int bottom
    );
}
