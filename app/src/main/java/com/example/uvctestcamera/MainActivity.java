package com.example.uvctestcamera;

import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.example.uvctestcamera.components.CameraPreview;
import com.example.uvctestcamera.container.database.Database;
import com.example.uvctestcamera.container.mqtt.MQTT;
import org.json.JSONException;

public class MainActivity extends AppCompatActivity {

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final boolean USE_SURFACE_ENCODER = false;
    private static final int PREVIEW_MODE = 1;

    private int mPreviewSurfaceId;
    private static final String TAG = "TLV";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG,"Reach MQTT config");

        MQTT.loadConfig(this);
        MQTT.db_handler = new Database(getApplicationContext());
        try {
            MQTT.db_handler.getAllUserSchedules();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        // Use dropUserScheduleTable and loadFacesfromSQL for debugging database
        MQTT.db_handler.dropUserScheduleTable();
//          MQTT.db_handler.loadFacesfromSQL();
        MQTT.start();

        ImageButton btnIdCard = findViewById(R.id.btn_id_card);
        ImageButton btnAdd = findViewById(R.id.btn_add);
        ImageButton btnCamera = findViewById(R.id.btn_camera);

        btnCamera.setOnClickListener(v -> {
            Log.d(TAG, "Camera button clicked");
            showFragment(new CameraPreview());
        });
        Log.d(TAG,"Here the camera Preview");
        showFragment(new CameraPreview());
    }

    private void showFragment(Fragment fragment){
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.main,fragment)
                .commit();
    }
}
