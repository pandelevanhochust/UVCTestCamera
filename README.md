Attendance System - NAVIS Device Android App

### 1️⃣ Context
This application allows

### 2️⃣ Containers
- **Mobile App (this project):** Android application responsible for camera input, face detection, and UI.
- **Local Database:** Manages attendance and user info using SQLite
- **MQTT Messaging:** Used to publish attendance data to MQTT broker

### 3️⃣ Components
- **Face Processing (faceprocessing/):** Loads and runs the TFLite model for face recognition.
- **Database (database/):** Handles SQLite/local storage.
- **UI Components (components/):** Manages camera preview and overlays.
- **Messaging (messaging/):** Sends data to remote MQTT topics.
- **Main Activity:** .

### 4️⃣ Code
Source code is modularized by functionality: `database/`, `mqtt/`, `faceprocessing/`, and `facedetection/`

## 🚀 Features
- Face detection using TensorFlow Lite
- Camera preview with live overlay
- Attendance timestamp logging that sends directly to MQTT broker




