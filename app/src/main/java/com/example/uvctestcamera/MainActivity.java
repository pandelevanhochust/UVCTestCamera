package com.example.uvctestcamera;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.Surface;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private UVCCameraTextureView cameraView;
    private Button captureBtn;
    private USBMonitor usbMonitor;
    private UVCCameraHandler cameraHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.camera_view);
        captureBtn = findViewById(R.id.capture_button);

        // Initialize camera handler
        cameraHandler = UVCCameraHandler.createHandler(
                this,
                cameraView,
                1,        // encoderType: 0 = YUV, 1 = MJPEG
                640,
                480,
                1,        // format
                1.0f      // bandwidth factor
        );

        // Init USB monitor
        usbMonitor = new USBMonitor(this, deviceConnectListener);

        // Capture button click
        captureBtn.setOnClickListener(v -> captureImage());
    }

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener =
            new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {
                    Toast.makeText(MainActivity.this, "USB Device Attached", Toast.LENGTH_SHORT).show();

                    // 🔐 Request permission BEFORE accessing anything
                    if (!usbMonitor.hasPermission(device)) {
                        usbMonitor.requestPermission(device);
                    }
                }

                @Override
                public void onConnect(UsbDevice device, UsbControlBlock ctrlBlock, boolean createNew) {
                    if (!usbMonitor.hasPermission(device)) {
                        Toast.makeText(MainActivity.this, "USB permission not granted", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        if (cameraHandler != null) {
                            cameraHandler.open(ctrlBlock);
                            cameraHandler.startPreview(new Surface(cameraView.getSurfaceTexture()));
                        }
                    } catch (SecurityException e) {
                        Toast.makeText(MainActivity.this, "Permission denied by system", Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Failed to open camera", Toast.LENGTH_LONG).show();
                        e.printStackTrace();
                    }
                }

                @Override
                public void onDisconnect(UsbDevice device, UsbControlBlock ctrlBlock) {
                    if (cameraHandler != null) {
                        cameraHandler.close();
                    }
                }

                @Override
                public void onDettach(UsbDevice device) {
                    Toast.makeText(MainActivity.this, "USB Device Detached", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onCancel(UsbDevice device) {
                    Toast.makeText(MainActivity.this, "USB Permission Cancelled", Toast.LENGTH_SHORT).show();
                }
            };

    @Override
    protected void onStart() {
        super.onStart();
        if (usbMonitor != null) {
            usbMonitor.register();
        }
    }

    @Override
    protected void onStop() {
        if (cameraHandler != null) {
            cameraHandler.close();
        }
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (cameraHandler != null) {
            cameraHandler.release();
        }
        if (usbMonitor != null) {
            usbMonitor.destroy();
        }
        super.onDestroy();
    }

    private void captureImage() {
        File dir = new File(getExternalFilesDir(null), "Pictures");
        if (!dir.exists()) dir.mkdirs();

        String filename = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        String filePath = new File(dir, filename).getAbsolutePath();

        if (cameraHandler != null) {
            cameraHandler.captureStill(filePath);
            Toast.makeText(this, "Image saved: " + filePath, Toast.LENGTH_SHORT).show();
        }
    }
}
