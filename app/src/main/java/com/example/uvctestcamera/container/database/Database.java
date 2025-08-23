    package com.example.uvctestcamera.container.database;

    import android.content.ContentValues;
    import android.content.Context;
    import android.database.Cursor;
    import android.database.sqlite.SQLiteDatabase;
    import android.database.sqlite.SQLiteOpenHelper;
    import android.graphics.Bitmap;
    import android.graphics.BitmapFactory;
    import android.util.Base64;
    import android.util.Log;
    import com.example.uvctestcamera.container.facedetection.FaceProcessor;
    import com.example.uvctestcamera.container.facedetection.Faces;
    import com.example.uvctestcamera.components.CameraPreview;
    import org.json.JSONArray;
    import org.json.JSONException;
    import org.json.JSONObject;

    import java.nio.ByteBuffer;
    import java.nio.ByteOrder;
    import java.text.ParseException;
    import java.text.SimpleDateFormat;
    import java.util.Date;
    import java.util.HashMap;
    import java.util.Locale;
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

        private static final String DEVICE_ID = "1c7c9da0-7c42-11f0-8715-c7d1f7b78287";

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
                            "end_time TEXT, "    +
                            "face_image BLOB, " +
                            "lecturer_id TEXT," +
                            "status TEXT," +
                            "face_embedding BLOB," +
                            "device_id TEXT)"
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

        public void insertUserSchedule(JSONObject params) throws JSONException, ParseException {
            SQLiteDatabase db = this.getWritableDatabase();

            SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
            SimpleDateFormat targetFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            String lecturerId = params.getString("LecturerId");
            String deviceId = params.getString("DeviceId");
            String startTime = params.getString("TimeStart");
            String endTime = params.getString("TimeEnd");
            Date parsedStart = isoFormat.parse(startTime);
            Date parsedEnd = isoFormat.parse(endTime);

            String formattedStart = targetFormat.format(parsedStart);
            String formattedEnd = targetFormat.format(parsedEnd);

            JSONArray students = params.getJSONArray("AttendanceStudents");

            for (int i = 0; i < students.length(); i++) {
                JSONObject studentObj = students.getJSONObject(i).getJSONObject("Student");

                String userId = studentObj.getString("UserId");
                String username = studentObj.getString("UserName");
                String identifyNumber = studentObj.getString("StudentCode");
                String faceImageBase64 = studentObj.getString("FaceImage"); //Base64 image
                String email = studentObj.getString("Email");

                byte[] faceImageBytes = null; //Convert base64 to bytes
                if (faceImageBase64 != null && !faceImageBase64.isEmpty()) {
//                    No need to substring anymore
                    if (faceImageBase64.startsWith("data:image")) {
                        faceImageBase64 = faceImageBase64.substring(faceImageBase64.indexOf(",") + 1);
                    }
                    try {
                        faceImageBytes = Base64.decode(faceImageBase64, Base64.DEFAULT);
                        Log.d(TAG, "Decoded face image, bytes length: " + faceImageBytes.length);

                        Bitmap bitmap = BitmapFactory.decodeByteArray(faceImageBytes, 0, faceImageBytes.length);

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

                        Faces.Recognition face = new Faces.Recognition(userId, username, 0f);
                        face.setExtra(new float[][]{embedding[0]}); //Float value
                        Log.d(TAG,"add the faces" + username +"-" + face.getExtra());

                        CameraPreview.savedFaces.put(username, face);

                        byte[] embeddingBytes = convertFloatArrayToByteArray(embedding[0]); // convert float to bytes

                        ContentValues values = new ContentValues();
                        values.put("user_id", userId);
                        values.put("username", username);
                        values.put("identify_number", identifyNumber);
                        values.put("start_time", formattedStart);
                        values.put("end_time", formattedEnd);
                        values.put("face_image", faceImageBytes);
                        values.put("lecturer_id",lecturerId);
//                      values.put("status",);
                        values.put("face_embedding", embeddingBytes);
                        values.put("device_id",deviceId);

                        db.insert(TABLE_NAME, null, values);
                        Log.d(TAG, "Inserted user " + username + " successfully");

                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Base64 decoding failed: " + e.getMessage());
                    }
                }
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
                obj.put("device_id", cursor.getString(cursor.getColumnIndexOrThrow("device_id")));

                byte[] face_image = cursor.getBlob(cursor.getColumnIndexOrThrow("face_image"));
                obj.put("face_image", face_image);

                Log.d(TAG, "Parsed row: " + obj);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing cursor: " + e.getMessage());
            }
            return obj;
        }

        //Find user with its Id
        public JSONObject findUserwithId(String userId) {
            Log.d(TAG, "Reach findUserwithId: " + userId);
            SQLiteDatabase db = this.getReadableDatabase();
            JSONObject result = null;

            Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_NAME + " WHERE user_id = ? ORDER BY start_time DESC LIMIT 1", new String[]{userId});

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    result = cursortoJSON(cursor);
                } else {
                    Log.w(TAG, "No user found with ID: " + userId);
                }
                cursor.close();
            } else {
                Log.e(TAG, "Query returned null cursor for ID: " + userId);
            }
            db.close();
            return result;
        }

        // Load Faces using for debugging database
        public void loadFacesfromSQL() {
            Log.d(TAG, "Reach loadFaces");
            CameraPreview.savedFaces.clear();
            SQLiteDatabase db = this.getReadableDatabase();
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            Log.d(TAG, "Querying with now=" + now + ", device_id=" + DEVICE_ID);


            String sql = "SELECT user_id, username, face_image, face_embedding " +
                    "FROM " + TABLE_NAME + " " +
                    "WHERE start_time <= ? AND end_time >= ? AND device_id = ?";
            Cursor cursor = db.rawQuery(sql, new String[]{now, now, DEVICE_ID});

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String userId = cursor.getString(cursor.getColumnIndexOrThrow("user_id"));
                    String username = cursor.getString(cursor.getColumnIndexOrThrow("username"));
                    byte[] embeddingBytes = cursor.getBlob(cursor.getColumnIndexOrThrow("face_embedding"));
                    byte[] faceImage = cursor.getBlob(cursor.getColumnIndexOrThrow("face_image"));

                    Log.d(TAG,"Here the byte image");

                    if(faceImage == null) {
                        Log.w(TAG, "No face image found for user: " + username);
                    }
                    if (embeddingBytes == null) {
                        Log.w(TAG, "No embedding found for user: " + username);
                        continue;
                    }

                    float[] embeddingFloat = convertByteArrayToFloatArray(embeddingBytes);

                    Faces.Recognition face = new Faces.Recognition(userId, username, 0f);
                    face.setExtra(new float[][]{embeddingFloat});

                    CameraPreview.savedFaces.put(username, face);
                }
                Log.d(TAG, "Cursor count: " + cursor.getCount());
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

        //These two for Float to Bytes Embedding Conversion
        private byte[] convertFloatArrayToByteArray(float[] input) {
            ByteBuffer buffer = ByteBuffer.allocate(input.length * 4);
            buffer.order(ByteOrder.nativeOrder());
            for (float f : input) buffer.putFloat(f);
            return buffer.array();
        }

        private float[] convertByteArrayToFloatArray(byte[] input) {
            ByteBuffer buffer = ByteBuffer.wrap(input).order(ByteOrder.nativeOrder());
            float[] output = new float[input.length / 4];
            buffer.asFloatBuffer().get(output);
            return output;
        }

        // Update user schedule with status (Check-in, Check-in late, Check-out)
        public void updateUserSchedule(String userId, String status) {
            SQLiteDatabase db = this.getWritableDatabase();

            ContentValues values = new ContentValues();
            values.put("status", status);

            int rowsAffected = db.update(
                    TABLE_NAME,
                    values,
                    "user_id = ?",
                    new String[]{userId}
            );

            if (rowsAffected > 0) {
                Log.d(TAG, "Updated status for user_id: " + userId + " to: " + status);
            } else {
                Log.w(TAG, "No row found to update for user_id: " + userId);
            }

            db.close();
        }

        public long getNextSessionEndTimeMillis(String deviceId) {
            SQLiteDatabase db = this.getReadableDatabase();
            long nextEndTime = -1;

            String nowFormatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            try (Cursor cursor = db.rawQuery(
                    "SELECT end_time FROM " + TABLE_NAME +
                            " WHERE device_id = ? AND end_time > ? ORDER BY end_time ASC LIMIT 1",
                    new String[]{deviceId, nowFormatted})) {

                if (cursor != null && cursor.moveToFirst()) {
                    String endTimeStr = cursor.getString(0);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    Date endDate = sdf.parse(endTimeStr);
                    if (endDate != null) {
                        nextEndTime = endDate.getTime();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "⚠️ Error parsing end_time or executing query", e);
            }

            return nextEndTime;
        }



    }
