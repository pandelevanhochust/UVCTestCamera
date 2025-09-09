package com.example.uvctestcamera.engine;

import androidx.annotation.Keep;

@Keep
public class ModelConfig {
    public float scale = 0f;
    public float shift_x = 0f;
    public float shift_y = 0f;
    public int height = 0;
    public int width = 0;
    public String name = "";
    public boolean org_resize = false;

    public ModelConfig() {}
}
