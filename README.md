Attendance System - NAVIS Device Android App

### 1️⃣ Context
This application allows

### 2️⃣ Containers
- **Mobile App (this project):** Android application responsible for camera input, face detection, and UI.
- **Local Database:** Manages attendance info using SQLite
- **MQTT Messaging:** Used to publish attendance data to MQTT broker

### 3️⃣ Components

- **Main Activity:**
- **Face Processing (faceprocessing/):** Loads and runs the TFLite model for face recognition.
- **Database (database/):** Handles SQLite local storage for attendance session: records, timestamps, attendants' status, bio, face embeddings
- **UI Components (components/):** Manages camera preview and face bounding box overlays.
- **MQTT (mqtt/):** Sends data to publish attendance messages to MQTT broker.

### 4️⃣ How to use


## 🚀 Features
- Face detection using TensorFlow Lite
- Camera preview with live overlay
- Attendance timestamp logging that sends directly to MQTT broker




