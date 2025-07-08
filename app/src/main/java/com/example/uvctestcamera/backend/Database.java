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

    import java.io.ByteArrayOutputStream;
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

        private static final int PREVIEW_WIDTH = 640;
        private static final int PREVIEW_HEIGHT = 480;

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
                            "face_image BLOB, " +
                            "lecturer_id TEXT)"
            );
        }

        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG,"Reach onUpgrade table");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }

        // Take the Schedule out of the database for debugging
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
            Log.d(TAG, "Total rows fetched: " + total);
            for(int i= 0; i < total; i++){
                Log.d(TAG, resultArray.getJSONObject(i).toString());
            }
        }

        public void insertUserSchedule(JSONObject params) throws JSONException {
            SQLiteDatabase db = this.getWritableDatabase();


            String lecturerId = params.getString("LecturerId");
            String DeviceId = params.getString("DeviceId");
            String startTime = params.getString("StartTime");
            String endTime = params.getString("EndTime");
            String CreatedAt = params.getString("CreatedAt");

            JSONArray students = params.getJSONArray("AttendanceStudents");
            for (int i = 0; i < students.length(); i++) {
                JSONObject studentObj = students.getJSONObject(i).getJSONObject("Student");

                String userId = studentObj.getString("UserId");
                String username = studentObj.getString("UserName");
                String identifyNumber = studentObj.getString("StudentCode");
                String faceImageBase64 = studentObj.getString("FaceImage");

                byte[] faceImageBytes = null;
                if (faceImageBase64 != null && !faceImageBase64.isEmpty()) {
                    if (faceImageBase64.startsWith("data:image")) {
                        faceImageBase64 = faceImageBase64.substring(faceImageBase64.indexOf(",") + 1);
                    }

                    try {
                        faceImageBytes = Base64.decode(faceImageBase64, Base64.DEFAULT);
                        Log.d(TAG, "Decoded face image, bytes length: " + faceImageBytes.length);
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
                values.put("lecturer_id",lecturerId);

                db.insert(TABLE_NAME, null, values);
                Log.d(TAG, "Inserted user " + username + " successfully");
            }

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
                obj.put("lecturer_id", cursor.getString(cursor.getColumnIndexOrThrow("lecturer_id")));

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

        // Load Faces using for debugging database
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

    //                Bitmap bmp = BitmapFactory.decodeFile("assets/temp.jpg");
    //                ByteArrayOutputStream blob = new ByteArrayOutputStream();
    //                bmp.compress(Bitmap.CompressFormat.PNG, 0 /* Ignored for PNGs */, blob);
    //                byte[] bitmapdata = blob.toByteArray();
    //                Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapdata, 0, bitmapdata.length);


                    if  (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                        Log.e(TAG, "Bitmap decode failed for user: " + username);
                        continue;
                    }
                    Log.d(TAG,"convert bytes to buffer success");

                    ByteBuffer input = FaceProcessor.convertBitmapToByteBufferinDatabase(bitmap);
                    float[][] embedding = new float[1][OUTPUT_SIZE];
                    Object[] inputArray = {input};
                    Map<Integer, Object> outputMap = new HashMap<>();
                    outputMap.put(0, embedding);

                    CameraPreview.tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

                    Faces.Recognition face = new Faces.Recognition(username, username, 0f);
                    face.setExtra(new float[][]{embedding[0]});
                    Log.d(TAG,"add the faces" + face.getExtra());
                    CameraPreview.savedFaces.put(username, face);
                }
                cursor.close();
            }
        }

        // Drop table if you want to update database
        public void dropUserScheduleTable() {
            Log.d(TAG, "Dropping user_schedule table...");
            SQLiteDatabase db = this.getWritableDatabase();
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            Log.d(TAG, "Table dropped. Recreating...");
            onCreate(db);
        }
    }
