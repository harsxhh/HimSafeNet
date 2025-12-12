package com.himsafenetmesh.ble

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.himsafenetmesh.mesh.MeshModule
import java.util.UUID

class BLESensorService : Service() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var isConnected = false
    
    // BLE UUIDs (must match ESP32 code)
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val DEVICE_NAME = "HimSafeNet-Sensor"
    
    private val handler = Handler(Looper.getMainLooper())
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    Log.d("BLESensor", "Connected to ESP32")
                    MeshModule.emitStatus("🔵 Connected to sensor")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    Log.d("BLESensor", "Disconnected from ESP32")
                    MeshModule.emitStatus("🔴 Disconnected from sensor")
                    // Try to reconnect
                    handler.postDelayed({ startScan() }, 3000)
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    
                    val descriptor = characteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    gatt.writeDescriptor(descriptor)
                    
                    Log.d("BLESensor", "Notifications enabled")
                }
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.getValue()
            if (data == null || data.isEmpty()) {
                Log.w("BLESensor", "Received empty data")
                return
            }
            
            val message = String(data, Charsets.UTF_8)
            Log.d("BLESensor", "Received: $message")
            
            // Parse the message
            if (message.startsWith("STATUS:")) {
                // Status update - just log it
                val distance = message.substring(7)
                Log.d("BLESensor", "Sensor distance: $distance cm")
            } else if (message.contains(":")) {
                // Alert message format: "distance:message"
                val parts = message.split(":", limit = 2)
                if (parts.size == 2) {
                    val distance = parts[0]
                    val alertText = parts[1]
                    
                    Log.d("BLESensor", "🚨 ALERT from sensor: $alertText (Distance: $distance cm)")
                    MeshModule.emitStatus("🚨 Sensor alert: $alertText")
                    
                    // Send alert to mesh network
                    sendAlertToMesh(alertText)
                }
            } else {
                // Direct alert message
                Log.d("BLESensor", "🚨 ALERT from sensor: $message")
                MeshModule.emitStatus("🚨 Sensor alert: $message")
                sendAlertToMesh(message)
            }
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == DEVICE_NAME && !isConnected) {
                Log.d("BLESensor", "Found sensor: ${device.address}")
                stopScan()
                connectToDevice(device)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE feature unsupported"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }
            Log.e("BLESensor", "Scan failed: $errorCode - $errorMessage")
            
            // Check if it's a permission error
            if (errorCode == 1 || errorCode == 2) { // Common permission error codes
                MeshModule.emitStatus("❌ BLE scan failed: Missing permissions. Please grant location permissions.")
            } else {
                MeshModule.emitStatus("❌ BLE scan failed: $errorMessage")
            }
            
            // Retry after a delay if it's not a permanent error
            if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                handler.postDelayed({
                    if (checkPermissions() && !isScanning && !isConnected) {
                        Log.d("BLESensor", "Retrying scan after failure...")
                        startScan()
                    }
                }, 3000)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e("BLESensor", "Bluetooth not enabled")
            MeshModule.emitStatus("❌ Bluetooth not enabled")
            return
        }
        
        // Check permissions before starting scan
        if (checkPermissions()) {
            startScan()
        } else {
            Log.w("BLESensor", "Missing permissions, cannot start scan")
            MeshModule.emitStatus("⚠️ Missing permissions for BLE scanning. Please grant location permissions.")
        }
    }
    
    private fun checkPermissions(): Boolean {
        // For Android 12+ (API 31+), we need BLUETOOTH_SCAN and BLUETOOTH_CONNECT
        // For older versions, we need location permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - BLUETOOTH_SCAN includes location permission implicitly
            val hasBluetoothScan = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasBluetoothConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            
            // On Android 12+, we might still need location for some BLE operations
            val hasLocation = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || 
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            return hasBluetoothScan && hasBluetoothConnect && hasLocation
        } else {
            // Android 11 and below - need location permissions
            // Either FINE or COARSE location is sufficient
            val hasFineLocation = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            // At least one location permission is required
            return hasFineLocation || hasCoarseLocation
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check permissions again when service is started/restarted
        if (checkPermissions()) {
            if (!isScanning && !isConnected) {
                handler.postDelayed({
                    startScan()
                }, 1000) // Wait a bit for permissions to be fully processed
            }
        } else {
            Log.w("BLESensor", "Permissions not granted, waiting...")
            MeshModule.emitStatus("⚠️ Waiting for location permissions...")
            // Retry after 2 seconds
            handler.postDelayed({
                if (checkPermissions() && !isScanning && !isConnected) {
                    startScan()
                }
            }, 2000)
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startScan() {
        if (isScanning || isConnected) return
        
        // Check permissions before scanning
        if (!checkPermissions()) {
            Log.e("BLESensor", "Cannot start scan: missing permissions")
            MeshModule.emitStatus("❌ Missing permissions for BLE scanning. Please grant location permissions.")
            
            // Log which permissions are missing for debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val missing = mutableListOf<String>()
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    missing.add("BLUETOOTH_SCAN")
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    missing.add("BLUETOOTH_CONNECT")
                }
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    missing.add("LOCATION (FINE or COARSE)")
                }
                Log.e("BLESensor", "Missing permissions: ${missing.joinToString(", ")}")
            } else {
                val hasFine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                Log.e("BLESensor", "Location permissions - FINE: $hasFine, COARSE: $hasCoarse")
            }
            return
        }
        
        // Final permission check right before scanning (Android BLE requires this)
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasFineLocation && !hasCoarseLocation) {
            Log.e("BLESensor", "CRITICAL: No location permission granted at scan time!")
            Log.e("BLESensor", "FINE_LOCATION: $hasFineLocation, COARSE_LOCATION: $hasCoarseLocation")
            MeshModule.emitStatus("❌ Location permission required! Please grant in app settings.")
            return
        }
        
        Log.d("BLESensor", "Location permissions OK - FINE: $hasFineLocation, COARSE: $hasCoarseLocation")
        
        try {
            // Try with service UUID filter first
            val filter = ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
            
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            
            // This is where Android BLE framework checks permissions
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d("BLESensor", "Started scanning for sensor with filter...")
            MeshModule.emitStatus("🔍 Scanning for sensor...")
        } catch (e: SecurityException) {
            // This catches permission errors from Android BLE framework
            Log.e("BLESensor", "SecurityException during scan (permission issue): ${e.message}")
            MeshModule.emitStatus("❌ Permission denied by Android. Please grant location permission in Settings.")
            isScanning = false
        } catch (e: Exception) {
            Log.e("BLESensor", "Failed to start scan with filter, trying without filter", e)
            // Fallback: scan without filter (will check device name in callback)
            try {
                // Check permissions again before fallback scan
                val hasFine = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                val hasCoarse = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasFine && !hasCoarse) {
                    Log.e("BLESensor", "No location permission for fallback scan")
                    MeshModule.emitStatus("❌ Location permission required!")
                    return
                }
                
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                bluetoothLeScanner?.startScan(null, settings, scanCallback)
                isScanning = true
                Log.d("BLESensor", "Started scanning for sensor without filter...")
                MeshModule.emitStatus("🔍 Scanning for sensor...")
            } catch (e2: SecurityException) {
                Log.e("BLESensor", "SecurityException in fallback scan: ${e2.message}")
                MeshModule.emitStatus("❌ Permission denied. Grant location permission in Settings → Apps → HimSafeNet Mesh → Permissions")
                isScanning = false
            } catch (e2: Exception) {
                Log.e("BLESensor", "Failed to start scan", e2)
                MeshModule.emitStatus("❌ Failed to start BLE scan: ${e2.message}")
                isScanning = false
            }
        }
    }
    
    private fun stopScan() {
        if (isScanning) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d("BLESensor", "Stopped scanning")
        }
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d("BLESensor", "Connecting to ${device.address}...")
        MeshModule.emitStatus("🔵 Connecting to sensor...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }
    
    private fun sendAlertToMesh(alertText: String) {
        // Send alert to mesh service
        val intent = Intent(this, com.himsafenetmesh.mesh.MeshService::class.java).apply {
            action = "SEND_ALERT"
            putExtra("text", alertText)
        }
        startService(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}

