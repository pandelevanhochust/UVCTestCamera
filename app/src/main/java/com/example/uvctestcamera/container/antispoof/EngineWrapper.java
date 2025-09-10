package com.example.uvctestcamera.container.antispoof;

import android.content.res.AssetManager;

import com.mv.engine.FaceBox;
import com.mv.engine.Live;

public class EngineWrapper {
    private final Live live;
    private boolean ready = false;

    public EngineWrapper(AssetManager am) {
        live = new Live();
        ready = live.loadModel(am) == 0;
    }

    public boolean isReady() { return ready; }

    public void destroy() {
        live.destroy();
    }

    public float livenessScore(byte[] nv21, int width, int height, int orientation, FaceBox box) {
        if (!ready) return 0f;
        return live.detect(nv21, width, height, orientation, box);
    }
}
