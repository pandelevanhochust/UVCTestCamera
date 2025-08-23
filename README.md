# Attendance System - SOICT IoT Device Android App

## Hướng dẫn sử dụng 

### 1. Kết nối với thiết bị
- Kết nối với nguồn để thiết bị lên
- Vào terminal repo, gõ cmd để debug trên LogCat

```bash
adb connect _diachiIP_:5555
adb devices 
```

### 2. Luồng thiết bị MainActivity
- Bắt đầu từ MainActivity, app sẽ tự động khởi tạo MQTT server(provisioning + config + subcribe to broker) và sau đó được đặt auto forward sang chế độ Camera Điểm Danh (CameraPreview).
- CameraPreview sẽ được khởi động, các hàm sẽ được chạy theo thứ tự
  - onViewCreated - function khởi tạo các lớp quan trọng: model nhận diện, load phiên từ database, khởi động layout Camera
  - Kết nối của camera usb được khởi động qua line
  ```java
  usbMonitor = new USBMonitor(requireContext(),deviceConnectListener);
  ```
  - deviceConnectListener sẽ gồm có những phương thức con từ thư viện của Camera UVC
  ```java
  @Override
  public void onAttach()  //Băt được kết nối USB và xin quyền cho app được truy cập Camera
  public void onConnect() // Kết nối với USB Camera thành công
  public void onDisconnect() // Vẫn cần bổ sung thêm tính năng 
  public void onDettach()    // cho các function của USB để xử lí các tình huống mất kết nối với USB
  public void onCancel()
  ```
  - Khi kết nối thành công, hàm onConnect() sẽ chạy hàm startPreview() để chính thức bật Camera lên
  - Ở đây một luồng (Thread) chạy hàm startImageProcessor() là một hàm Callback để analyze từng frame thông qua function onFrame()
  - App đang được set cứ cách 5 frame thì lấy 1 frame và gửi tới detectFace() để nhận diện
  - Nếu frame đó nhận diện được mặt thì sẽ được gửi tới onFacesDetected() để vẽ boudning box cũng như gửi tới hàm recognize() để đối chiếu với những gương mặt có trong phiên và gửi tới MQTT broker
  - Cần tối ưu lại các hàm hoạt động do quá nhiểu

### 3. MQTT 
- Lớp MQTT luôn được khởi động khi App bắt đầu chạy
- MQTT được set theo config sẵn trong app
- MQTT sẽ provision tới broker, nhận credential rồi subscribe tới topics mà broker hiện có
- Khi nhận diện khuôn mặt thành công MQTT sẽ gửi gói tin với telemetry_topic là check-in, check-in late hay check-out cùng với thông tin cơ bản của sinh viên

```java
MqttMessage message = new MqttMessage(wrappedPayload.toString().getBytes());
message.setQos(pubQos);
client.publish(telemetry_topic, message);
```

- Khi nhận được message từ server xuống, MQTT sẽ thông qua function handleRPC() để lưu lại data gửi tơí vào SQLite

### 4. Database SQLite
- Đây là các thuộc tính được lưu trong database 
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
user_id TEXT
username TEXT
identify_number TEXT
start_time TEXT
end_time TEXT
face_image BLOB
lecturer_id TEXT
status TEXT
face_embedding BLOB                            
device_id TEXT                         
```
- Khi nhận được bản tin từ MQTT, thông tin sẽ được lưu về SQLite, trong đó ảnh gương mặt(hiện là base64) sẽ được convert về dạng byte[] để lưu vào face_image và conver về vector để lưu vào face_embedding để tiện cho việc nhận diện




## Overview App Structure

### 1️⃣ Context

### 2️⃣ Containers
- **Mobile App (this project):** Android application responsible for camera input, face detection, and UI.
- **Face Processing (faceprocessing/):** Loads and runs the TFLite model for face recognition.
- **Database (database/):** Handles SQLite local storage for attendance session: records, timestamps, attendants' status, bio, face embeddings
- **MQTT (mqtt/):** Sends data to publish attendance messages to MQTT broker.

### 3️⃣ Components
- **UI Components (components/):** Manages camera preview and face bounding box overlays.

### 4️⃣ How to use

## 🚀 Features
- Face detection using TensorFlow Lite
- Camera preview with live overlay
- Attendance timestamp logging that sends directly to MQTT broker

### Detail
- App Name: UVCTestCamera





