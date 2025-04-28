package com.example.uvctestcamera.backend;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.util.Map;
import java.util.UUID;

public class MQTT {

    private static MqttClient client;
    private static String telemetry_topic;
    private static String rpc_topic;
    private static String provision_request_topic;
    private static String provision_response_topic;
    private static String provision_device_key;
    private static String provision_device_secret;
    private static String attribute_topic;
    private static int pubQos = 1;
    private static int subQos = 1;
    private static String broker;
    private static String clientId = "TLV";
    private static boolean provisioned = false;
    private static String host;
    private static String port;

    private static Context appContext;

    private static final String TAG = "MQTT";

    public static void loadConfig(Context context) {
        Log.d(TAG,"Reach loadingConfig");
        try {
            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open("config.json");

            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, String> config = objectMapper.readValue(inputStream, Map.class);

            telemetry_topic = config.get("telemetry_topic");
            provision_request_topic = config.get("provision_request_topic");
            provision_response_topic = config.get("provision_response_topic");
            attribute_topic = config.get("attribute_topic");
            rpc_topic = "v1/devices/me/rpc/request/+";
            provision_device_key = config.get("provision_device_key");
            provision_device_secret = config.get("provision_device_secret");

            host = config.get("host");
            port = String.valueOf(config.get("port"));
            broker = "tcp://" + host + ":" + port;

            appContext = context.getApplicationContext();

            Log.d(TAG,broker);

            inputStream.close();

            Log.d(TAG,"Reach loadingConfig");
            System.out.println("[MQTT] Loaded config from assets.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void start() {
        Log.d(TAG,"Reach start");
        try {
            if (credentialsExist()) {
                connectWithCredentials();
            } else {
                provisionDevice();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void provisionDevice() {
        Log.d(TAG, "[Provision] No credentials found. Start provisioning...");

        new Thread(() -> {
            MqttClient provisionClient = null;
            try {
                provisionClient = new MqttClient(broker, "provision_" + UUID.randomUUID(), new MemoryPersistence());

                MqttConnectOptions options = new MqttConnectOptions();
                options.setCleanSession(true);
                options.setUserName("provision");
                options.setKeepAliveInterval(60);
                provisionClient.connect(options);

                MqttClient finalProvisionClient = provisionClient; // for inner class
                provisionClient.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        Log.e(TAG, "[Provision] Connection lost: " + cause.getMessage());
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) throws Exception {
                        String payload = new String(message.getPayload());
                        Log.d(TAG, "[Provision] Received provision response: " + payload);

                        JSONObject json = new JSONObject(payload);
                        if ("SUCCESS".equalsIgnoreCase(json.optString("status"))) {
                            String credentials = json.getString("credentialsValue");
                            Log.d(TAG, "[Provision] Received credentials: " + credentials);

                            saveCredentials(appContext, credentials);
                            provisioned = true;

                            // Disconnect provision client after successful provisioning
                            new Thread(() -> {
                                try {
                                    if (finalProvisionClient.isConnected()) {
                                        finalProvisionClient.disconnect();
                                        Log.d(TAG, "[Provision] Provision client disconnected cleanly.");
                                    }
                                    finalProvisionClient.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                                try {
                                    connectWithCredentials();
                                } catch (MqttException e) {
                                    throw new RuntimeException(e);
                                }
                            }).start();
                        } else {
                            Log.e(TAG, "[Provision] Provision failed: " + json.optString("errorMsg"));
                            try {
                                if (finalProvisionClient.isConnected()) {
                                    finalProvisionClient.disconnect();
                                }
                                finalProvisionClient.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        Log.d(TAG, "[Provision] Delivery complete.");
                    }
                });

                provisionClient.subscribe(provision_response_topic, subQos);

                JSONObject provisionRequest = new JSONObject();
                provisionRequest.put("provisionDeviceKey", provision_device_key);
                provisionRequest.put("provisionDeviceSecret", provision_device_secret);
                provisionRequest.put("deviceName", generateDeviceName());

                MqttMessage provisionMessage = new MqttMessage(provisionRequest.toString().getBytes());
                provisionMessage.setQos(pubQos);
                provisionClient.publish(provision_request_topic, provisionMessage);

                Log.d(TAG, "[Provision] Sent provision request: " + provisionRequest.toString());

            } catch (Exception e) {
                Log.e(TAG, "[Provision] Exception: " + e.getMessage());
                e.printStackTrace();

                try {
                    if (provisionClient != null && provisionClient.isConnected()) {
                        provisionClient.disconnect();
                        provisionClient.close();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }).start();
    }

    private static void connectWithCredentials() throws MqttException {
       Log.d(TAG,"[MQTT] Connecting with credentials...");

        client = new MqttClient(broker, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(getCredentials(appContext, clientId));
        options.setExecutorServiceTimeout(60);
        options.setCleanSession(true);
        client.connect(options);

        if (client.isConnected()) {
           Log.d(TAG,"[MQTT] Connected to broker: " + broker);

            client.setCallback(new MqttCallback() {
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String payload = new String(message.getPayload());
                    Log.d(TAG,"[MQTT] Received RPC on topic: " + topic);
                    Log.d(TAG,"[MQTT] Message: " + payload);
                    handleRpc(topic, payload);
                }

                public void connectionLost(Throwable cause) {
                   Log.d(TAG,"[connect with Credential] Connection lost: " + cause.getMessage());
                   retryConnect();
                }

                public void deliveryComplete(IMqttDeliveryToken token) {
                   Log.d(TAG,"[MQTT] Delivery complete.");
                }
            });

            Log.d(TAG,"Reach subscribe");
            Log.d(TAG,rpc_topic);

            client.subscribe(rpc_topic, subQos);

            Log.d(TAG,"Reach subscribe");
            new Thread(() -> sendTelemetryLoop()).start();
        }
    }

    private static void handleRpc(String topic, String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String method = json.optString("method");
            JSONObject params = json.optJSONObject("params");

            if (method.equals("getState") || method.equals("twoWay")) {
                JSONObject response = new JSONObject();
                response.put("status", "active");

                String responseTopic = topic.replace("request", "response");
                client.publish(responseTopic, new MqttMessage(response.toString().getBytes()));
                System.out.println("[RPC] Replied device state.");
            } else if (method.equals("userSchedule")) {
                System.out.println("[RPC] userSchedule params: " + params.toString());
                JSONObject response = new JSONObject().put("response", "ok");

                String responseTopic = topic.replace("request", "response");
                client.publish(responseTopic, new MqttMessage(response.toString().getBytes()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendTelemetryLoop() {
        Log.d(TAG,"[MQTT] Send telemetry loop");
        while (true) {
            try {
                JSONObject telemetry = new JSONObject();
                JSONObject status = new JSONObject();
                status.put("timestamp", System.currentTimeMillis());
                telemetry.put("status", status);

                MqttMessage message = new MqttMessage(telemetry.toString().getBytes());
                message.setQos(pubQos);
                client.publish(telemetry_topic, message);

                System.out.println("[Telemetry] Sent: " + telemetry.toString());
                Thread.sleep(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean credentialsExist() {
        return new File("credentials").exists();
    }

    private static String getCredentials(Context context, String defaultUsername) {
        Log.d(TAG,"Reach Save Credential or default");
        try {
            File file = new File(context.getFilesDir(), "credentials");
            FileInputStream fis = new FileInputStream(file);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            fis.close();
            return baos.toString().trim();
        } catch (IOException e) {
            Log.e(TAG,"Failed loading credential");
            return defaultUsername;
        }
    }

    private static void saveCredentials(Context context, String credentials) {
        Log.d(TAG,"Reach Save Credential");
        try {
            File file = new File(context.getFilesDir(), "credentials");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(credentials.getBytes());
            fos.close();
            Log.d(TAG,"Save Credential successfully");
        } catch (IOException e) {
            Log.d(TAG,"Save Credential failed");
            e.printStackTrace();
        }
    }

    public static void cleanCredentials() {
        try {
            File file = new File(appContext.getFilesDir(), "credentials");
            if (file.exists()) {
                boolean deleted = file.delete();
                if (deleted) {
                    Log.d(TAG, "[MQTT] Credentials file deleted successfully.");
                } else {
                    Log.e(TAG, "[MQTT] Failed to delete credentials file.");
                }
            } else {
                Log.d(TAG, "[MQTT] No credentials file found to delete.");
            }
        } catch (Exception e) {
            Log.e(TAG, "[MQTT] Exception while deleting credentials: " + e.getMessage());
        }
    }

    private static String generateDeviceName() {
        long mac = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                (mac >> 40) & 0xff, (mac >> 32) & 0xff, (mac >> 24) & 0xff,
                (mac >> 16) & 0xff, (mac >> 8) & 0xff, mac & 0xff);
    }

    public static void sendFaceMatch(String timestamp, String username) {
        try {
            if (client == null || !client.isConnected()) {
                Log.e(TAG, "[sendFaceMatch] MQTT Client not connected. Skip sending face match.");
                return;
            }

            JSONObject payload = new JSONObject();
//            JSONObject entryExit = new JSONObject();
            payload.put("timestamp", timestamp);
            payload.put("username", username);
//            entryExit.put("id", UUID.randomUUID().toString());
//            payload.put("entry_exit_history", entryExit);

            MqttMessage message = new MqttMessage(payload.toString().getBytes());
            message.setQos(1);
            client.publish(telemetry_topic, message);

            Log.d(TAG,"[Manual Face Match] Sent: " + payload.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void retryConnect() {
        new Thread(() -> {
            while (true) {
                try {
                    System.out.println("[MQTT] Attempting reconnect...");

                    client = new MqttClient(broker, clientId, new MemoryPersistence());
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setUserName(getCredentials(appContext, clientId));
                    options.setExecutorServiceTimeout(60);
                    options.setCleanSession(true);
                    options.setKeepAliveInterval(60);

                    client.connect(options);

                    if (client.isConnected()) {
                        System.out.println("[MQTT] Reconnected successfully!");

                        client.setCallback(new MqttCallback() {
                            public void connectionLost(Throwable cause) {
                                retryConnect();
                            }
                            public void messageArrived(String topic, MqttMessage message) throws Exception {
                                handleRpc(topic, new String(message.getPayload()));
                            }
                            public void deliveryComplete(IMqttDeliveryToken token) { }
                        });

                        client.subscribe(rpc_topic, subQos);
                        break;
                    }
                } catch (Exception e) {
                    System.out.println("[MQTT] Reconnect failed: " + e.getMessage());
                    try {
                        Thread.sleep(5000); // ⏳ Wait 5 seconds before retry
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }).start();
    }

//    private static void provisionDevice() throws MqttException {
//        Log.d(TAG,"[Provision] No credentials found. Start provisioning...");
//
//        MqttClient provisionClient = new MqttClient(broker, "provision_" + UUID.randomUUID(), new MemoryPersistence());
//        MqttConnectOptions options = new MqttConnectOptions();
//        options.setCleanSession(true);
//        options.setUserName("provision");
//        options.setKeepAliveInterval(60);
//        provisionClient.connect(options);
//
//        provisionClient.setCallback(new MqttCallback() {
//            @Override
//            public void connectionLost(Throwable cause) {
//                Log.e(TAG,"[Provision] Connection lost: " + cause.getMessage());
//            }
//
//            @Override
//            public void messageArrived(String topic, MqttMessage message) throws Exception {
//                String payload = new String(message.getPayload());
//                Log.d(TAG,"[Provision] Received provision response: " + payload);
//
//                JSONObject json = new JSONObject(payload);
//                if ("SUCCESS".equalsIgnoreCase(json.optString("status"))) {
//                    String credentials = json.getString("credentialsValue");
//                    Log.d(TAG,"[Provision] Received credentials: " + credentials);
//
//                    saveCredentials(appContext, credentials);
//                    provisioned = true;
//
//                    // 💥 NO disconnect provisionClient
//                    // 💥 Directly connect real client
//                    connectWithCredentials();
//                } else {
//                    Log.e(TAG, "[Provision] Provision failed: " + json.optString("errorMsg"));
//                }
//            }
//
//            @Override
//            public void deliveryComplete(IMqttDeliveryToken token) {
//                Log.d(TAG,"[Provision] Delivery complete.");
//            }
//        });
//
//        provisionClient.subscribe(provision_response_topic, subQos);
//
//        JSONObject provisionRequest = new JSONObject();
//        provisionRequest.put("provisionDeviceKey", provision_device_key);
//        provisionRequest.put("provisionDeviceSecret", provision_device_secret);
//        provisionRequest.put("deviceName", generateDeviceName());
//
//        MqttMessage provisionMessage = new MqttMessage(provisionRequest.toString().getBytes());
//        provisionMessage.setQos(pubQos);
//        provisionClient.publish(provision_request_topic, provisionMessage);
//
//        Log.d(TAG,"[Provision] Sent provision request: " + provisionRequest.toString());
//    }

}
