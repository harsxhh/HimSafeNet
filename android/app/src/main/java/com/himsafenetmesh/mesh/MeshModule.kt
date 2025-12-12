package com.himsafenetmesh.mesh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

class MeshModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {
  override fun getName(): String = "Mesh"

  init {
    reactContextRef = ctx
  }

  companion object {
    @Volatile
    var reactContextRef: ReactApplicationContext? = null

    fun emitAlert(id: String, text: String, timestamp: Long, ttl: Int) {
      val rc = reactContextRef ?: return
      val params = Arguments.createMap().apply {
        putString("id", id)
        putString("text", text)
        putDouble("timestamp", timestamp.toDouble())
        putInt("ttl", ttl)
      }
      rc.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("AlertReceived", params)
    }

    fun emitStatus(message: String) {
      val rc = reactContextRef ?: return
      val params = Arguments.createMap().apply {
        putString("message", message)
      }
      rc.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("MeshStatus", params)
    }
  }

  @ReactMethod
  fun startService(promise: Promise) {
    val intent = Intent(ctx, MeshService::class.java)
    ctx.startForegroundService(intent)
    promise.resolve(null)
  }

  @ReactMethod
  fun requestPermissions(promise: Promise) {
    val activity = ctx.currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "No current activity")
      return
    }

    val permissions = arrayOf(
      Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.NEARBY_WIFI_DEVICES,
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.BLUETOOTH_CONNECT,
      Manifest.permission.BLUETOOTH_SCAN,
      Manifest.permission.POST_NOTIFICATIONS,
      Manifest.permission.WAKE_LOCK,
      Manifest.permission.USE_FULL_SCREEN_INTENT
    )

    // Check which permissions are already granted
    val permissionsToRequest = permissions.filter { permission ->
      ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED
    }

    if (permissionsToRequest.isEmpty()) {
      emitStatus("✅ All permissions already granted")
      promise.resolve(null)
      return
    }

    emitStatus("🔐 Requesting permissions: ${permissionsToRequest.joinToString(", ")}")
    
    val requestCode = 1001
    ActivityCompat.requestPermissions(activity as Activity, permissionsToRequest.toTypedArray(), requestCode)
    promise.resolve(null)
  }

  @ReactMethod
  fun sendAlert(text: String, promise: Promise) {
    val intent = Intent(ctx, MeshService::class.java).apply {
      action = "SEND_ALERT"
      putExtra("text", text)
    }
    ctx.startService(intent)
    promise.resolve(null)
  }

  @ReactMethod
  fun startBLESensor(promise: Promise) {
    val activity = ctx.currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "No current activity")
      return
    }

    // Determine required permissions based on Android version
    val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      // Android 12+ (API 31+)
      // BLUETOOTH_SCAN and BLUETOOTH_CONNECT are required
      // Location is still recommended for BLE scanning
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION // Request both, but either is fine
      )
    } else {
      // Android 11 and below
      // Need at least one location permission (FINE or COARSE)
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    }

    val missingPermissions = requiredPermissions.filter { permission ->
      ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED
    }

    if (missingPermissions.isNotEmpty()) {
      emitStatus("🔐 Requesting BLE permissions: ${missingPermissions.joinToString(", ")}")
      ActivityCompat.requestPermissions(
        activity as Activity,
        missingPermissions.toTypedArray(),
        1002 // Different request code for BLE
      )
      emitStatus("📍 Please grant location permissions in the dialog that appears")
    } else {
      emitStatus("✅ All BLE permissions already granted")
    }

    // Start the service - it will check permissions and start scanning when ready
    val intent = Intent(ctx, com.himsafenetmesh.ble.BLESensorService::class.java)
    ctx.startService(intent)
    promise.resolve(null)
  }
}


