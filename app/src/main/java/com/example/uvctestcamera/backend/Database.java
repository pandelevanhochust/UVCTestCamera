package com.example.uvctestcamera.backend;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import com.example.uvctestcamera.FaceProcessor;
import com.example.uvctestcamera.Faces;
import com.example.uvctestcamera.UIComponents.CameraPreview;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class Database extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 2;
    private static final String DATABASE_NAME = "database.db";
    private static final String TABLE_NAME = "user_schedule";

    private static final int INPUT_SIZE = 112;
    private static final int OUTPUT_SIZE = 192;

    private static final String TAG = "Database";

    public Database(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "user_id TEXT, " +
                        "username TEXT, " +
                        "identify_number TEXT, " +
                        "start_time TEXT, " +
                        "end_time TEXT, " +
                        "face_image BLOB)"
        );
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG,"Reach onUpgrade table");
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    public void getAllUserSchedules() throws JSONException {
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME, null);

        JSONArray resultArray = new JSONArray();
        int total = 0;

        while (cursor.moveToNext()) {
            JSONObject row = cursortoJSON(cursor);
            resultArray.put(row);
            total++;
        }

        cursor.close();
        db.close();
//        Log.d(TAG, "Total rows fetched: " + total);
//        for(int i= 0; i < total; i++){
//            Log.d(TAG, resultArray.getJSONObject(i).toString());
//        }
    }

    public void insertUserSchedule(JSONObject params) throws JSONException {
        SQLiteDatabase db = this.getWritableDatabase();

        String userId = params.getString("userId");
        String username = params.getString("username");
        String identifyNumber = params.getString("identifyNumber");
        String startTime = params.getString("startTime");
        String endTime = params.getString("endTime");

        byte[] faceImageBytes = null;
        String faceImageBase64 = params.getString("faceImage");
        Log.d(TAG,"Param image: " + faceImageBase64);

        if (faceImageBase64 != null && !faceImageBase64.isEmpty()) {
            if (faceImageBase64.startsWith("data:image")) {
                Log.d(TAG,"Subtracting + image: Yes");
                faceImageBase64 = faceImageBase64.substring(faceImageBase64.indexOf(",") + 1);
            }

            try {
                faceImageBytes = Base64.decode(faceImageBase64, Base64.DEFAULT);
                Log.d(TAG, "Decoded face image, bytes: " + faceImageBytes.toString());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Base64 decoding failed: " + e.getMessage());
            }
        }

        ContentValues values = new ContentValues();
        values.put("user_id", userId);
        values.put("username", username);
        values.put("identify_number", identifyNumber);
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("face_image", faceImageBytes);
//        values.put("face_image", faceImageBase64);

        db.insert(TABLE_NAME, null, values);
        db.close();
        Log.d(TAG, "Insert user schedule complete");
    }

    private JSONObject cursortoJSON(Cursor cursor) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
            obj.put("user_id", cursor.getString(cursor.getColumnIndexOrThrow("user_id")));
            obj.put("username", cursor.getString(cursor.getColumnIndexOrThrow("username")));
            obj.put("identify_number", cursor.getString(cursor.getColumnIndexOrThrow("identify_number")));
            obj.put("start_time", cursor.getString(cursor.getColumnIndexOrThrow("start_time")));
            obj.put("end_time", cursor.getString(cursor.getColumnIndexOrThrow("end_time")));

            byte[] face_image = cursor.getBlob(cursor.getColumnIndexOrThrow("face_image"));

//            String encodedImage = (face_image != null)
//                    ? Base64.encodeToString(face_image, Base64.NO_WRAP)
//                    : null;

            obj.put("face_image", face_image);
            Log.d(TAG, "Parsed row: " + obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error parsing cursor: " + e.getMessage());
        }
        return obj;
    }

    public void loadFacesfromSQL() {
        Log.d(TAG, "Reach loadFaces");
        CameraPreview.savedFaces.clear();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT username, face_image FROM " + TABLE_NAME, null);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String username = cursor.getString(cursor.getColumnIndexOrThrow("username"));
                byte[] imageBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("face_image"));
                Log.d(TAG,"Here the byte image");

                if (imageBlob == null) {
                    Log.w(TAG, "No image found for user: " + username);
                    continue;
                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBlob, 0, imageBlob.length);
                if (bitmap == null) {
                    Log.e(TAG, "Bitmap decode failed for user: " + username);
                    continue;
                }

                ByteBuffer input = FaceProcessor.convertBitmapToByteBuffer(bitmap);
                float[][] embedding = new float[1][OUTPUT_SIZE];
                Object[] inputArray = {input};
                Map<Integer, Object> outputMap = new HashMap<>();
                outputMap.put(0, embedding);

                CameraPreview.tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

                Faces.Recognition face = new Faces.Recognition(username, username, 0f);
                face.setExtra(new float[][]{embedding[0]});
                CameraPreview.savedFaces.put(username, face);
            }
            cursor.close();
        }
    }

    public void dropUserScheduleTable() {
        Log.d(TAG, "Dropping user_schedule table...");
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        Log.d(TAG, "Table dropped. Recreating...");
        onCreate(db);  // Optional: recreate the table after dropping
    }
}
