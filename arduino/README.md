# ESP32 Ultrasonic Sensor Setup Guide

This guide will help you set up the ESP32 with an ultrasonic sensor to automatically trigger flood alerts.

## Hardware Required

1. **ESP32 Development Board** (ESP32-WROOM-32 or similar)
2. **HC-SR04 Ultrasonic Sensor**
3. **Jumper wires**
4. **USB cable** for programming ESP32

## Wiring Diagram

Connect the HC-SR04 ultrasonic sensor to ESP32:

```
HC-SR04    ->    ESP32
VCC        ->    5V (or 3.3V if your ESP32 supports it)
GND        ->    GND
Trig       ->    GPIO 5
Echo       ->    GPIO 18
```

## Software Setup

### 1. Install Arduino IDE

1. Download and install [Arduino IDE](https://www.arduino.cc/en/software) (version 1.8.19 or later)

### 2. Install ESP32 Board Support

1. Open Arduino IDE
2. Go to **File → Preferences**
3. In "Additional Board Manager URLs", add:
   ```
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
4. Click OK
5. Go to **Tools → Board → Boards Manager**
6. Search for "ESP32"
7. Install "esp32" by Espressif Systems
8. Click Install

### 3. Install Required Library

1. Go to **Tools → Manage Libraries**
2. Search for "ESP32 BLE Arduino"
3. Install "ESP32 BLE Arduino" by Neil Kolban

### 4. Upload Code

1. Connect ESP32 to your computer via USB
2. Select the board: **Tools → Board → ESP32 Arduino → ESP32 Dev Module**
3. Select the port: **Tools → Port → (your ESP32 port)**
4. Open the file: `esp32_ultrasonic_ble.ino`
5. Click **Upload** button (or press Ctrl+U)

### 5. Configure Settings (Optional)

You can modify these settings in the code:

- **DISTANCE_THRESHOLD**: Change from `50` to your desired threshold (in cm)
  - Example: `#define DISTANCE_THRESHOLD 30` for 30cm threshold
- **ALERT_MESSAGE**: Change the alert message
  - Example: `#define ALERT_MESSAGE "⚠️ Water level rising!"`
- **Sensor Pins**: Change TRIG_PIN and ECHO_PIN if needed

## Testing

1. Open **Serial Monitor** in Arduino IDE (Tools → Serial Monitor)
2. Set baud rate to **115200**
3. You should see:
   - "ESP32 Ultrasonic BLE Alert System Starting..."
   - "BLE Service started. Waiting for connection..."
   - Distance readings every 500ms

4. When distance < threshold, you'll see:
   - "ALERT! Distance: X cm - Below threshold!"
   - "Alert sent via BLE!"

## Connecting to Android App

1. Make sure ESP32 is powered and running
2. Open the HimSafeNet Mesh app on your Android device
3. Ensure Bluetooth is enabled on your phone
4. Click the **"🔵 Start Sensor"** button in the app
5. The app will automatically:
   - Scan for the ESP32 sensor
   - Connect when found
   - Receive alerts when distance threshold is crossed

## Troubleshooting

### ESP32 not found by app
- Make sure ESP32 is powered on
- Check Serial Monitor to confirm BLE is advertising
- Restart the app and try "Start Sensor" again
- Ensure Bluetooth is enabled on your phone

### No distance readings
- Check wiring connections
- Verify sensor is powered (LED on HC-SR04 should be on)
- Check Serial Monitor for error messages
- Try different GPIO pins if needed

### False alerts
- Increase DISTANCE_THRESHOLD value
- Check for obstacles in front of sensor
- Ensure sensor is mounted securely

### Connection drops
- Keep ESP32 and phone within Bluetooth range (~10 meters)
- Check battery/power supply for ESP32
- The app will automatically try to reconnect

## How It Works

1. **ESP32 continuously measures distance** using ultrasonic sensor
2. **When distance < threshold**, ESP32 sends alert via BLE
3. **Android app receives alert** and automatically broadcasts to mesh network
4. **All connected devices** receive the flood alert notification

## Power Options

- **USB Power**: Connect to computer or USB power adapter
- **Battery**: Use a 5V battery pack with USB output
- **Solar**: Use a solar panel with battery backup for outdoor installations

## Placement Tips

- Mount sensor **facing downward** toward water level
- Place at **desired flood detection height**
- Ensure **clear line of sight** to water surface
- Protect from **direct sunlight and rain** if outdoors
- Keep within **Bluetooth range** of Android device (~10m)

## Advanced Configuration

### Change Scan Interval
In the Arduino code, modify:
```cpp
delay(500); // Check every 500ms
```

### Change Status Update Interval
Modify:
```cpp
if (deviceConnected && (millis() - lastStatusUpdate > 5000)) {
```
Change `5000` to your desired interval in milliseconds.

### Multiple Sensors
You can set up multiple ESP32 sensors with different device names:
```cpp
BLEDevice::init("HimSafeNet-Sensor-1");
BLEDevice::init("HimSafeNet-Sensor-2");
// etc.
```

Then modify the Android app to scan for multiple device names.

---

**Need Help?** Check the main project README or open an issue on GitHub.

