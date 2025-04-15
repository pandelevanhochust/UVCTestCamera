package com.example.uvctestcamera;

import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.example.uvctestcamera.UIComponents.CameraPreview;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usbcameracommon.UVCCameraHandlerMultiSurface;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.content.ContentValues.TAG;
import static com.serenegiant.uvccamera.BuildConfig.DEBUG;

public class MainActivity extends AppCompatActivity {

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final boolean USE_SURFACE_ENCODER = false;
    private static final int PREVIEW_MODE = 1;

    private int mPreviewSurfaceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ImageButton btnIdCard = findViewById(R.id.btn_id_card);
        ImageButton btnAdd = findViewById(R.id.btn_add);
        ImageButton btnCamera = findViewById(R.id.btn_camera);

        btnCamera.setOnClickListener(v -> {
            Log.d(TAG, "Camera button clicked");
            showFragment(new CameraPreview());
        });
    }

    private void showFragment(Fragment fragment){
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.main,fragment)
                .commit();
    }
}
