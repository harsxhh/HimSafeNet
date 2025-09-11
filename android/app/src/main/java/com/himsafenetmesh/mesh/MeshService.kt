package com.himsafenetmesh.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AlertMessage(
  val id: String,
  val text: String,
  val timestamp: Long,
  val ttl: Int
)

class MeshService : Service() {
  private val strategy = Strategy.P2P_STAR
  private val connections by lazy { Nearby.getConnectionsClient(this) }
  private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
  private val seenAlertIds = ConcurrentHashMap.newKeySet<String>()
  private val serviceId = "com.himsafenetmesh.mesh"
  private val endpointName = Build.MODEL ?: "Android"

  override fun onCreate() {
    super.onCreate()
    android.util.Log.d("MeshService", "onCreate called")
    MeshModule.emitStatus("🔧 Starting mesh service...")
    startForegroundNotification()
    
    // Start advertising first, then discovery with a small delay
    startAdvertising()
    
    // Start discovery after a short delay to ensure advertising is ready
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      startDiscovery()
    }, 1000)
    
    // Add periodic status logging
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      logDetailedStatus()
    }, 5000)
    
    logStatus()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "SEND_ALERT") {
      val text = intent.getStringExtra("text") ?: "Emergency alert"
      sendAlert(text)
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startForegroundNotification() {
    try {
      val channelId = "mesh"
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        nm.createNotificationChannel(NotificationChannel(channelId, "Mesh", NotificationManager.IMPORTANCE_LOW))
      }
      val notif: Notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("HimSafeNet Mesh")
        .setContentText("Relaying alerts offline")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .build()
      startForeground(1, notif)
      android.util.Log.d("MeshService", "Foreground notification started")
      MeshModule.emitStatus("✅ Foreground service started")
    } catch (e: Exception) {
      android.util.Log.e("MeshService", "Failed to start foreground notification", e)
      MeshModule.emitStatus("❌ Failed to start foreground service: ${e.message}")
    }
  }

  private val payloadCallback = object : PayloadCallback() {
    override fun onPayloadReceived(endpointId: String, payload: Payload) {
      android.util.Log.d("MeshService", "🎯 PAYLOAD RECEIVED from: $endpointId")
      android.util.Log.d("MeshService", "🎯 Payload type: ${payload.type}")
      android.util.Log.d("MeshService", "🎯 Payload ID: ${payload.id}")
      
      payload.asBytes()?.let { bytes ->
        val json = String(bytes, StandardCharsets.UTF_8)
        android.util.Log.d("MeshService", "📨 Received JSON: $json")
        android.util.Log.d("MeshService", "📨 JSON length: ${json.length}")
        handleIncoming(json)
      } ?: run {
        android.util.Log.w("MeshService", "⚠️ Received payload with no bytes from: $endpointId")
      }
    }
    override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
      android.util.Log.d("MeshService", "Payload transfer update: $endpointId, status: ${update.status}")
    }
  }

  private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
    override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
      android.util.Log.d("MeshService", "🤝 CONNECTION INITIATED with: $endpointId")
      android.util.Log.d("MeshService", "🤝 Endpoint name: ${info.endpointName}")
      android.util.Log.d("MeshService", "🤝 Authentication token: ${info.authenticationToken}")
      MeshModule.emitStatus("🤝 Connection initiated with: $endpointId")
      
      android.util.Log.d("MeshService", "✅ Accepting connection from: $endpointId")
      connections.acceptConnection(endpointId, payloadCallback)
        .addOnSuccessListener {
          android.util.Log.d("MeshService", "✅ Connection accepted: $endpointId")
          MeshModule.emitStatus("✅ Connection accepted: $endpointId")
        }
        .addOnFailureListener { e ->
          android.util.Log.e("MeshService", "❌ Failed to accept connection: $endpointId", e)
          MeshModule.emitStatus("❌ Failed to accept connection: ${e.message}")
        }
    }
    override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
      android.util.Log.d("MeshService", "📊 CONNECTION RESULT for $endpointId: ${result.status}")
      if (result.status.isSuccess) {
        connectedEndpoints.add(endpointId)
        android.util.Log.d("MeshService", "✅ CONNECTED to peer: $endpointId")
        MeshModule.emitStatus("✅ Connected to peer: $endpointId")
        logStatus()
      } else {
        android.util.Log.w("MeshService", "❌ FAILED to connect to peer: $endpointId, status: ${result.status}")
        android.util.Log.w("MeshService", "❌ Status code: ${result.status.statusCode}")
        MeshModule.emitStatus("❌ Failed to connect to peer: ${result.status}")
      }
    }
    override fun onDisconnected(endpointId: String) {
      connectedEndpoints.remove(endpointId)
      android.util.Log.d("MeshService", "❌ DISCONNECTED from peer: $endpointId")
      MeshModule.emitStatus("❌ Disconnected from peer: $endpointId")
      logStatus()
    }
  }

  private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
      android.util.Log.d("MeshService", "🔍 FOUND PEER: $endpointId, name: ${info.endpointName}")
      android.util.Log.d("MeshService", "🔍 Service ID: ${info.serviceId}")
      MeshModule.emitStatus("🔍 Found peer: ${info.endpointName}")
      
      // Skip if already connected
      if (connectedEndpoints.contains(endpointId)) {
        android.util.Log.d("MeshService", "🔄 Already connected to: $endpointId")
        return
      }
      
      android.util.Log.d("MeshService", "🤝 Requesting connection to: $endpointId")
      connections.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
        .addOnSuccessListener {
          android.util.Log.d("MeshService", "✅ Connection request sent to: $endpointId")
        }
        .addOnFailureListener { e ->
          android.util.Log.e("MeshService", "❌ Failed to request connection to: $endpointId", e)
          MeshModule.emitStatus("❌ Failed to request connection: ${e.message}")
          
          // Retry connection after 2 seconds
          android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.util.Log.d("MeshService", "🔄 Retrying connection to: $endpointId")
            connections.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
          }, 2000)
        }
    }
    override fun onEndpointLost(endpointId: String) {
      android.util.Log.d("MeshService", "❌ Lost peer: $endpointId")
      MeshModule.emitStatus("❌ Lost peer: $endpointId")
    }
  }

  private fun startAdvertising() {
    android.util.Log.d("MeshService", "Starting advertising with strategy: $strategy")
    android.util.Log.d("MeshService", "Service ID: $serviceId, Endpoint: $endpointName")
    
    val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
    connections.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
      .addOnSuccessListener { 
        android.util.Log.d("MeshService", "✅ Started advertising successfully")
        MeshModule.emitStatus("📡 Started advertising - waiting for peers...")
      }
      .addOnFailureListener { 
        android.util.Log.e("MeshService", "❌ Failed to start advertising", it)
        MeshModule.emitStatus("❌ Failed to start advertising: ${it.message}")
      }
  }

  private fun startDiscovery() {
    android.util.Log.d("MeshService", "Starting discovery with strategy: $strategy")
    android.util.Log.d("MeshService", "Looking for service ID: $serviceId")
    
    val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
    connections.startDiscovery(serviceId, endpointDiscoveryCallback, options)
      .addOnSuccessListener { 
        android.util.Log.d("MeshService", "✅ Started discovery successfully")
        MeshModule.emitStatus("🔍 Started discovery - looking for peers...")
      }
      .addOnFailureListener { 
        android.util.Log.e("MeshService", "❌ Failed to start discovery", it)
        MeshModule.emitStatus("❌ Failed to start discovery: ${it.message}")
      }
  }

  fun sendAlert(text: String) {
    val msg = AlertMessage(
      id = UUID.randomUUID().toString(),
      text = text,
      timestamp = System.currentTimeMillis(),
      ttl = 8
    )
    val json = serialize(msg)
    broadcast(json)
    // Don't show our own alert in UI, just broadcast it
  }

  private fun handleIncoming(json: String) {
    android.util.Log.d("MeshService", "🔍 Processing incoming JSON: $json")
    val msg = deserialize(json)
    if (msg == null) {
      android.util.Log.e("MeshService", "❌ Failed to deserialize JSON: $json")
      return
    }
    android.util.Log.d("MeshService", "✅ Deserialized alert: ${msg.text} (ID: ${msg.id})")
    
    if (!seenAlertIds.add(msg.id)) {
      android.util.Log.d("MeshService", "🔄 Duplicate alert ignored: ${msg.id}")
      return
    }
    
    android.util.Log.d("MeshService", "🚨 EMITTING ALERT TO UI: ${msg.text}")
    onAlertReceived(msg)
    
    if (msg.ttl > 1) {
      val next = msg.copy(ttl = msg.ttl - 1)
      android.util.Log.d("MeshService", "📡 Relaying alert (TTL: ${next.ttl})")
      broadcast(serialize(next))
    } else {
      android.util.Log.d("MeshService", "⏹️ Alert TTL expired, not relaying")
    }
  }

  private fun broadcast(json: String) {
    val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
    android.util.Log.d("MeshService", "📡 Broadcasting to ${connectedEndpoints.size} peers")
    android.util.Log.d("MeshService", "📡 Broadcasting JSON: $json")
    android.util.Log.d("MeshService", "📡 Payload size: ${json.toByteArray(StandardCharsets.UTF_8).size} bytes")
    MeshModule.emitStatus("📡 Broadcasting to ${connectedEndpoints.size} peers")
    
    connectedEndpoints.forEach { eid -> 
      android.util.Log.d("MeshService", "📤 Sending payload to peer: $eid")
      connections.sendPayload(eid, payload)
        .addOnSuccessListener {
          android.util.Log.d("MeshService", "✅ Successfully sent to peer: $eid")
        }
        .addOnFailureListener { e ->
          android.util.Log.e("MeshService", "❌ Failed to send to peer: $eid", e)
          MeshModule.emitStatus("❌ Failed to send to peer: $eid")
        }
    }
  }

  private fun serialize(msg: AlertMessage): String {
    val escapedText = msg.text.replace("\"", "\\\"")
    return """{"id":"${msg.id}","text":"$escapedText","timestamp":${msg.timestamp},"ttl":${msg.ttl}}"""
  }

  private fun deserialize(json: String): AlertMessage? = try {
    val pairs = json.trim('{', '}').split(",")
    val map = mutableMapOf<String, String>()
    for (p in pairs) {
      val kv = p.split(":", limit = 2)
      map[kv[0].trim().trim('"')] = kv[1].trim()
    }
    AlertMessage(
      id = map["id"]!!.trim('"'),
      text = map["text"]!!.trim('"').replace("\\\"", "\""),
      timestamp = map["timestamp"]!!.toLong(),
      ttl = map["ttl"]!!.toInt()
    )
  } catch (_: Exception) { null }

  private fun onAlertReceived(msg: AlertMessage) {
    // Emit to JS UI via NativeModule event
    MeshModule.emitAlert(msg.id, msg.text, msg.timestamp, msg.ttl)
  }

  private fun logStatus() {
    android.util.Log.d("MeshService", "Connected peers: ${connectedEndpoints.size}")
  }
  
  private fun logDetailedStatus() {
    android.util.Log.d("MeshService", "📊 DETAILED STATUS:")
    android.util.Log.d("MeshService", "📊 Connected peers: ${connectedEndpoints.size}")
    android.util.Log.d("MeshService", "📊 Endpoint name: $endpointName")
    android.util.Log.d("MeshService", "📊 Service ID: $serviceId")
    android.util.Log.d("MeshService", "📊 Strategy: $strategy")
    MeshModule.emitStatus("📊 Status: ${connectedEndpoints.size} peers connected")
  }

  override fun onDestroy() {
    super.onDestroy()
    connections.stopAdvertising()
    connections.stopDiscovery()
    connections.stopAllEndpoints()
  }
}


