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
      Manifest.permission.BLUETOOTH_SCAN
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
}


