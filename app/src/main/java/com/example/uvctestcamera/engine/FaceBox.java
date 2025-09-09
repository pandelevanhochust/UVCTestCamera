package com.example.uvctestcamera.engine;

import androidx.annotation.Keep;

@Keep
public class FaceBox {
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public float confidence;

    public FaceBox(int left, int top, int right, int bottom, float confidence) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
    }
}
