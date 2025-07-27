Attendance System - NAVIS Device Android App

### 1️⃣ Context
This application allows users (students/employees) to mark attendance via face detection. Data is stored locally and optionally sent to a backend system via MQTT.

### 2️⃣ Containers
- **Mobile App (this project):** Android application responsible for camera input, face detection, and UI.
- **Local Database:** Manages attendance and user info.
- **MQTT Messaging:** Used to publish attendance data to a remote server (optional).

### 3️⃣ Components
- **Face Processing (faceprocessing/):** Loads and runs the TFLite model for face recognition.
- **Database (database/):** Handles SQLite/local storage.
- **UI Components (components/):** Manages camera preview and overlays.
- **Messaging (messaging/):** Sends data to remote MQTT topics.
- **Main Activity:** Orchestrates lifecycle and system integration.

### 4️⃣ Code
Source code is modularized by functionality: `database/`, `messaging/`, `faceprocessing/`, and `components/`.

---

## 🚀 Features

- Face detection using TensorFlow Lite
- Camera preview with live overlay
- Attendance timestamp logging
- MQTT integration (optional)

---

## 📁 Project Structure (Simplified)

