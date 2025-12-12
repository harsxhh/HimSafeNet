/*
 * ESP32 Ultrasonic Sensor with BLE Alert System
 * For HimSafeNet Mesh Network
 * 
 * Hardware:
 * - ESP32 Development Board
 * - HC-SR04 Ultrasonic Sensor
 *   - VCC -> 5V
 *   - GND -> GND
 *   - Trig -> GPIO 5
 *   - Echo -> GPIO 18
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// Ultrasonic Sensor Pins
#define TRIG_PIN 5
#define ECHO_PIN 18

// BLE Configuration
#define SERVICE_UUID "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// Distance Threshold (in cm) - adjust as needed
#define DISTANCE_THRESHOLD 50  // 50cm = 0.5 meters

// Alert message
#define ALERT_MESSAGE "🚨 Flood Alert! Water detected nearby!"

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// BLE Server Callbacks
class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("Device connected");
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("Device disconnected");
    }
};

// Function to measure distance
float measureDistance() {
    // Clear the trig pin
    digitalWrite(TRIG_PIN, LOW);
    delayMicroseconds(2);
    
    // Set trig pin HIGH for 10 microseconds
    digitalWrite(TRIG_PIN, HIGH);
    delayMicroseconds(10);
    digitalWrite(TRIG_PIN, LOW);
    
    // Read the echo pin
    long duration = pulseIn(ECHO_PIN, HIGH);
    
    // Calculate distance in cm
    // Speed of sound = 343 m/s = 0.0343 cm/μs
    // Distance = (duration * 0.0343) / 2
    float distance = (duration * 0.0343) / 2;
    
    return distance;
}

void setup() {
    Serial.begin(115200);
    Serial.println("ESP32 Ultrasonic BLE Alert System Starting...");
    
    // Setup ultrasonic sensor pins
    pinMode(TRIG_PIN, OUTPUT);
    pinMode(ECHO_PIN, INPUT);
    
    // Initialize BLE
    BLEDevice::init("HimSafeNet-Sensor");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());
    
    // Create BLE Service
    BLEService* pService = pServer->getServiceByUUID(SERVICE_UUID);
    if (pService == nullptr) {
        pService = pServer->createService(SERVICE_UUID);
    }
    
    // Create BLE Characteristic
    pCharacteristic = pService->createCharacteristic(
        CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    
    pCharacteristic->addDescriptor(new BLE2902());
    
    // Start the service
    pService->start();
    
    // Start advertising
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);  // helps with iPhone connections issue
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();
    
    Serial.println("BLE Service started. Waiting for connection...");
    Serial.println("Device name: HimSafeNet-Sensor");
}

void loop() {
    // Handle BLE connection state
    if (!deviceConnected && oldDeviceConnected) {
        delay(500); // give the bluetooth stack the chance to get things ready
        pServer->startAdvertising(); // restart advertising
        Serial.println("Restarting advertising...");
        oldDeviceConnected = deviceConnected;
    }
    
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }
    
    // Measure distance
    float distance = measureDistance();
    
    // Check if distance is below threshold
    if (distance > 0 && distance < DISTANCE_THRESHOLD && distance < 400) { // 400cm max range
        Serial.print("ALERT! Distance: ");
        Serial.print(distance);
        Serial.println(" cm - Below threshold!");
        
        // Send alert via BLE if connected
        if (deviceConnected) {
            String alertData = String(distance, 2) + ":" + ALERT_MESSAGE;
            pCharacteristic->setValue(alertData.c_str());
            pCharacteristic->notify();
            
            Serial.println("Alert sent via BLE!");
            Serial.println(alertData);
        }
        
        // Wait a bit to avoid spam
        delay(2000);
    } else {
        // Normal operation - just log distance
        Serial.print("Distance: ");
        Serial.print(distance);
        Serial.println(" cm - OK");
        
        // Send status update every 5 seconds if connected
        static unsigned long lastStatusUpdate = 0;
        if (deviceConnected && (millis() - lastStatusUpdate > 5000)) {
            String statusData = "STATUS:" + String(distance, 2);
            pCharacteristic->setValue(statusData.c_str());
            pCharacteristic->notify();
            lastStatusUpdate = millis();
        }
    }
    
    delay(500); // Check every 500ms
}

