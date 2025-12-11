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
  private val lostEndpoints = ConcurrentHashMap<String, Long>() // Track lost endpoints with timestamp
  private val serviceId = "com.himsafenetmesh.mesh"
  private val endpointName = Build.MODEL ?: "Android"
  private var isAdvertising = false
  private var isDiscovering = false
  private var isStoppingDiscovery = false
  private var pendingDiscoveryStart = false
  private val discoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

  override fun onCreate() {
    super.onCreate()
    android.util.Log.d("MeshService", "onCreate called")
    MeshModule.emitStatus("🔧 Starting mesh service...")
    startForegroundNotification()
    
    // Start advertising first
    startAdvertising()
    
    // Start discovery after a delay to avoid "out of order" errors
    discoveryHandler.postDelayed({
      startDiscovery()
    }, 2000)
    
    // Start periodic tasks
    startPeriodicDiscovery()
    startPeriodicStatusCheck()
    
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
      val channelId = "mesh_service"
      val alertChannelId = "mesh_alerts"
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Service notification channel (low priority)
        val serviceChannel = NotificationChannel(
          channelId, 
          "Mesh Service", 
          NotificationManager.IMPORTANCE_LOW
        ).apply {
          description = "Background mesh networking service"
          setShowBadge(false)
        }
        
        // Alert notification channel (high priority)
        val alertChannel = NotificationChannel(
          alertChannelId, 
          "Emergency Alerts", 
          NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Emergency alerts from nearby devices"
          enableLights(true)
          enableVibration(true)
          setShowBadge(true)
        }
        
        nm.createNotificationChannel(serviceChannel)
        nm.createNotificationChannel(alertChannel)
      }
      
      val notif: Notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("HimSafeNet Mesh")
        .setContentText("Relaying alerts offline - ${connectedEndpoints.size} peers")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
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
        // Pass the sender endpointId to prevent sending back to them
        handleIncoming(json, endpointId)
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
        // Remove from lost endpoints if reconnected
        lostEndpoints.remove(endpointId)
        android.util.Log.d("MeshService", "✅ CONNECTED to peer: $endpointId")
        MeshModule.emitStatus("✅ Connected to peer: $endpointId")
        updateForegroundNotification()
        logStatus()
      } else {
        android.util.Log.w("MeshService", "❌ FAILED to connect to peer: $endpointId, status: ${result.status}")
        android.util.Log.w("MeshService", "❌ Status code: ${result.status.statusCode}")
        MeshModule.emitStatus("❌ Failed to connect to peer: ${result.status}")
        
        // Mark as lost for potential reconnection
        if (!connectedEndpoints.contains(endpointId)) {
          lostEndpoints[endpointId] = System.currentTimeMillis()
        }
      }
    }
    override fun onDisconnected(endpointId: String) {
      val wasConnected = connectedEndpoints.remove(endpointId)
      
      if (wasConnected) {
        // Track lost endpoint for potential reconnection
        lostEndpoints[endpointId] = System.currentTimeMillis()
        android.util.Log.d("MeshService", "❌ DISCONNECTED from peer: $endpointId")
        MeshModule.emitStatus("❌ Disconnected from peer: $endpointId")
        updateForegroundNotification()
        logStatus()
        
        // Ensure discovery is running for reconnection
        if (!isDiscovering && !isStoppingDiscovery) {
          android.util.Log.d("MeshService", "🔄 Restarting discovery for reconnection...")
          startDiscovery()
        }
        
        // Attempt to reconnect after a delay - discovery should find it again
        reconnectHandler.postDelayed({
          if (!connectedEndpoints.contains(endpointId)) {
            android.util.Log.d("MeshService", "🔄 Checking reconnection status for: $endpointId")
            // Discovery should have found it by now if it's available
            // If still not connected and it's been less than 2 minutes, keep trying
            val lostTime = lostEndpoints[endpointId]
            if (lostTime != null && System.currentTimeMillis() - lostTime < 120000) {
              if (!isDiscovering && !isStoppingDiscovery) {
                android.util.Log.d("MeshService", "🔄 Restarting discovery to find lost peer...")
                startDiscovery()
              }
            } else if (lostTime != null) {
              // Remove old lost endpoints (older than 2 minutes)
              lostEndpoints.remove(endpointId)
              android.util.Log.d("MeshService", "⏹️ Removed old lost endpoint: $endpointId")
            }
          }
        }, 5000)
      }
    }
  }

  private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
      android.util.Log.d("MeshService", "🔍 FOUND PEER: $endpointId, name: ${info.endpointName}")
      android.util.Log.d("MeshService", "🔍 Service ID: ${info.serviceId}")
      
      // Skip if already connected
      if (connectedEndpoints.contains(endpointId)) {
        android.util.Log.d("MeshService", "🔄 Already connected to: $endpointId")
        // Remove from lost endpoints if it was there
        lostEndpoints.remove(endpointId)
        return
      }
      
      // Check if this was a previously lost endpoint (reconnection)
      val wasLost = lostEndpoints.containsKey(endpointId)
      if (wasLost) {
        android.util.Log.d("MeshService", "🔄 Reconnecting to previously lost peer: $endpointId")
        MeshModule.emitStatus("🔄 Reconnecting to: ${info.endpointName}")
        lostEndpoints.remove(endpointId)
      } else {
        MeshModule.emitStatus("🔍 Found peer: ${info.endpointName}")
      }
      
      android.util.Log.d("MeshService", "🤝 Requesting connection to: $endpointId")
      connections.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
        .addOnSuccessListener {
          android.util.Log.d("MeshService", "✅ Connection request sent to: $endpointId")
        }
        .addOnFailureListener { e ->
          android.util.Log.e("MeshService", "❌ Failed to request connection to: $endpointId", e)
          MeshModule.emitStatus("❌ Failed to request connection: ${e.message}")
          
          // Retry connection after 3 seconds, but only if not already connected
          if (!connectedEndpoints.contains(endpointId)) {
            connectionHandler.postDelayed({
              android.util.Log.d("MeshService", "🔄 Retrying connection to: $endpointId")
              connections.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { retryError ->
                  android.util.Log.e("MeshService", "❌ Retry failed for: $endpointId", retryError)
                  // Mark as lost if retry fails
                  if (!connectedEndpoints.contains(endpointId)) {
                    lostEndpoints[endpointId] = System.currentTimeMillis()
                  }
                }
            }, 3000)
          }
        }
    }
    override fun onEndpointLost(endpointId: String) {
      android.util.Log.d("MeshService", "❌ Lost peer: $endpointId")
      
      // Remove from connected if it was connected
      val wasConnected = connectedEndpoints.remove(endpointId)
      
      if (wasConnected) {
        MeshModule.emitStatus("❌ Lost peer: $endpointId")
        updateForegroundNotification()
        logStatus()
      }
      
      // Track lost endpoint for potential reconnection
      lostEndpoints[endpointId] = System.currentTimeMillis()
      
      // Ensure discovery continues to run for reconnection
      if (wasConnected && !isDiscovering && !isStoppingDiscovery) {
        android.util.Log.d("MeshService", "🔄 Restarting discovery after peer loss...")
        startDiscovery()
      }
    }
  }

  private fun startAdvertising() {
    if (isAdvertising) {
      android.util.Log.d("MeshService", "Already advertising, skipping")
      return
    }
    
    android.util.Log.d("MeshService", "Starting advertising with strategy: $strategy")
    android.util.Log.d("MeshService", "Service ID: $serviceId, Endpoint: $endpointName")
    
    val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
    connections.startAdvertising(endpointName, serviceId, connectionLifecycleCallback, options)
      .addOnSuccessListener { 
        isAdvertising = true
        android.util.Log.d("MeshService", "✅ Started advertising successfully")
        MeshModule.emitStatus("📡 Started advertising - waiting for peers...")
      }
      .addOnFailureListener { 
        isAdvertising = false
        android.util.Log.e("MeshService", "❌ Failed to start advertising", it)
        MeshModule.emitStatus("❌ Failed to start advertising: ${it.message}")
        
        // Retry advertising after 5 seconds
        connectionHandler.postDelayed({
          android.util.Log.d("MeshService", "🔄 Retrying advertising...")
          startAdvertising()
        }, 5000)
      }
  }

  private fun startDiscovery() {
    // If already discovering, skip
    if (isDiscovering) {
      android.util.Log.d("MeshService", "Already discovering, skipping")
      return
    }
    
    // If currently stopping, mark that we want to start after stop completes
    if (isStoppingDiscovery) {
      android.util.Log.d("MeshService", "Discovery is stopping, will start after stop completes")
      pendingDiscoveryStart = true
      return
    }
    
    android.util.Log.d("MeshService", "Starting discovery with strategy: $strategy")
    android.util.Log.d("MeshService", "Looking for service ID: $serviceId")
    
    val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
    connections.startDiscovery(serviceId, endpointDiscoveryCallback, options)
      .addOnSuccessListener { 
        isDiscovering = true
        pendingDiscoveryStart = false
        android.util.Log.d("MeshService", "✅ Started discovery successfully")
        MeshModule.emitStatus("🔍 Started discovery - looking for peers...")
      }
      .addOnFailureListener { e ->
        isDiscovering = false
        pendingDiscoveryStart = false
        android.util.Log.e("MeshService", "❌ Failed to start discovery", e)
        android.util.Log.e("MeshService", "❌ Error details: ${e.message}")
        
        // Check if it's an "already discovering" error - this means the flag is out of sync
        val isAlreadyDiscovering = e.message?.contains("already discovering", ignoreCase = true) ?: false
        if (isAlreadyDiscovering) {
          android.util.Log.w("MeshService", "⚠️ Discovery already running (flag out of sync), syncing state...")
          // Sync the state - discovery is actually running
          isDiscovering = true
          MeshModule.emitStatus("🔍 Discovery already running")
        } else {
          MeshModule.emitStatus("❌ Failed to start discovery: ${e.message}")
          // Retry after a delay for other errors
          discoveryHandler.postDelayed({
            android.util.Log.d("MeshService", "🔄 Retrying discovery...")
            startDiscovery()
          }, 5000)
        }
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
    // When we send our own alert, don't exclude anyone (null means broadcast to all)
    broadcast(json, null)
    // Don't show our own alert in UI, just broadcast it
  }

  private fun handleIncoming(json: String, senderEndpointId: String) {
    android.util.Log.d("MeshService", "🔍 Processing incoming JSON: $json")
    android.util.Log.d("MeshService", "🔍 Sender endpoint: $senderEndpointId")
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
    
    // Relay the message to other peers, but exclude the sender to prevent loops
    if (msg.ttl > 1) {
      val next = msg.copy(ttl = msg.ttl - 1)
      android.util.Log.d("MeshService", "📡 Relaying alert (TTL: ${next.ttl}) - excluding sender: $senderEndpointId")
      // Exclude the sender to prevent sending the message back to them
      broadcast(serialize(next), senderEndpointId)
    } else {
      android.util.Log.d("MeshService", "⏹️ Alert TTL expired, not relaying")
    }
  }

  private fun broadcast(json: String, excludeEndpointId: String? = null) {
    val payload = Payload.fromBytes(json.toByteArray(StandardCharsets.UTF_8))
    
    // Filter out the excluded endpoint (sender) to prevent message loops
    val recipients = if (excludeEndpointId != null) {
      connectedEndpoints.filter { it != excludeEndpointId }
    } else {
      connectedEndpoints.toList()
    }
    
    android.util.Log.d("MeshService", "📡 Broadcasting to ${recipients.size} peers (excluding: ${excludeEndpointId ?: "none"})")
    android.util.Log.d("MeshService", "📡 Broadcasting JSON: $json")
    android.util.Log.d("MeshService", "📡 Payload size: ${json.toByteArray(StandardCharsets.UTF_8).size} bytes")
    // Don't emit status about broadcast recipients - it confuses the UI
    // Instead, just log it and emit the actual connection status
    android.util.Log.d("MeshService", "📡 Connected peers: ${connectedEndpoints.size}, Broadcasting to: ${recipients.size}")
    
    if (recipients.isEmpty()) {
      android.util.Log.d("MeshService", "⚠️ No recipients to broadcast to (all peers excluded or none connected)")
      // Still emit connection status to keep UI accurate
      MeshModule.emitStatus("📊 Status: ${connectedEndpoints.size} peers connected")
      return
    }
    
    recipients.forEach { eid -> 
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
    
    // Emit actual connection status after broadcasting to keep UI accurate
    MeshModule.emitStatus("📊 Status: ${connectedEndpoints.size} peers connected")
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
    
    // Show push notification
    showAlertNotification(msg)
  }
  
  private fun showAlertNotification(msg: AlertMessage) {
    try {
      val channelId = "mesh_alerts"
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      
      // Create a unique notification ID based on alert ID
      val notificationId = msg.id.hashCode()
      
      val notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("🚨 Emergency Alert")
        .setContentText(msg.text)
        .setStyle(NotificationCompat.BigTextStyle()
          .bigText("Emergency Alert: ${msg.text}\n\nReceived from nearby device via mesh network"))
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, android.R.drawable.ic_dialog_alert))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setFullScreenIntent(createFullScreenIntent(msg), true)
        .build()
      
      nm.notify(notificationId, notification)
      android.util.Log.d("MeshService", "📱 Push notification shown for alert: ${msg.text}")
      
    } catch (e: Exception) {
      android.util.Log.e("MeshService", "Failed to show alert notification", e)
    }
  }
  
  private fun createFullScreenIntent(msg: AlertMessage): android.app.PendingIntent {
    val intent = Intent(this, com.himsafenetmesh.MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra("alert_text", msg.text)
      putExtra("alert_id", msg.id)
    }
    
    return android.app.PendingIntent.getActivity(
      this, 
      msg.id.hashCode(), 
      intent, 
      android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
    )
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
    android.util.Log.d("MeshService", "📊 Advertising: $isAdvertising")
    android.util.Log.d("MeshService", "📊 Discovering: $isDiscovering")
    MeshModule.emitStatus("📊 Status: ${connectedEndpoints.size} peers connected")
    
    // Update foreground notification with current status
    updateForegroundNotification()
  }
  
  private fun updateForegroundNotification() {
    try {
      val channelId = "mesh_service"
      val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      
      val statusText = when {
        connectedEndpoints.isEmpty() -> "Searching for nearby devices..."
        connectedEndpoints.size == 1 -> "Connected to 1 device"
        else -> "Connected to ${connectedEndpoints.size} devices"
      }
      
      val notif: Notification = NotificationCompat.Builder(this, channelId)
        .setContentTitle("HimSafeNet Mesh")
        .setContentText(statusText)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
      
      nm.notify(1, notif)
    } catch (e: Exception) {
      android.util.Log.e("MeshService", "Failed to update foreground notification", e)
    }
  }
  
  private fun startPeriodicDiscovery() {
    // Restart discovery periodically to find new peers and reconnect to lost ones
    discoveryHandler.postDelayed(object : Runnable {
      override fun run() {
        // Clean up old lost endpoints (older than 2 minutes)
        val now = System.currentTimeMillis()
        val removed = lostEndpoints.entries.removeIf { (_, timestamp) -> now - timestamp > 120000 }
        if (removed) {
          android.util.Log.d("MeshService", "🧹 Cleaned up old lost endpoints")
        }
        
        // Only restart discovery if:
        // 1. We have lost endpoints we're trying to reconnect to, OR
        // 2. We have no connections and need to find peers
        // AND discovery is not currently running or stopping
        val shouldRestart = (lostEndpoints.isNotEmpty() || connectedEndpoints.isEmpty()) && 
                           !isDiscovering && !isStoppingDiscovery
        
        if (shouldRestart) {
          android.util.Log.d("MeshService", "🔄 Periodic discovery restart (lost: ${lostEndpoints.size}, connected: ${connectedEndpoints.size})")
          startDiscovery()
        } else {
          android.util.Log.d("MeshService", "⏸️ Skipping periodic restart (discovering: $isDiscovering, stopping: $isStoppingDiscovery)")
        }
        
        discoveryHandler.postDelayed(this, 30000) // 30 seconds
      }
    }, 30000)
  }
  
  private fun stopDiscovery() {
    if (!isDiscovering) {
      android.util.Log.d("MeshService", "Not discovering, nothing to stop")
      return
    }
    
    if (isStoppingDiscovery) {
      android.util.Log.d("MeshService", "Already stopping discovery, skipping")
      return
    }
    
    isStoppingDiscovery = true
    android.util.Log.d("MeshService", "Stopping discovery...")
    
    try {
      connections.stopDiscovery()
      android.util.Log.d("MeshService", "✅ Discovery stopped successfully")
      isDiscovering = false
      isStoppingDiscovery = false
      
      // If there was a pending start request, start now
      if (pendingDiscoveryStart) {
        android.util.Log.d("MeshService", "Starting discovery after stop (pending request)")
        discoveryHandler.postDelayed({
          startDiscovery()
        }, 1000) // Wait 1 second after stop before starting
      }
    } catch (e: Exception) {
      android.util.Log.e("MeshService", "❌ Exception stopping discovery", e)
      // Even if stop fails, mark as not discovering to avoid getting stuck
      isDiscovering = false
      isStoppingDiscovery = false
      
      // If there was a pending start, try to start anyway
      if (pendingDiscoveryStart) {
        discoveryHandler.postDelayed({
          startDiscovery()
        }, 2000)
      }
    }
  }
  
  private fun stopAdvertising() {
    if (isAdvertising) {
      android.util.Log.d("MeshService", "Stopping advertising...")
      connections.stopAdvertising()
      isAdvertising = false
    }
  }
  
  private fun startPeriodicStatusCheck() {
    // Check status every 10 seconds
    connectionHandler.postDelayed(object : Runnable {
      override fun run() {
        logDetailedStatus()
        
        // Restart advertising if it stopped
        if (!isAdvertising) {
          android.util.Log.d("MeshService", "🔄 Restarting advertising...")
          startAdvertising()
        }
        
        // Only restart discovery if it's actually stopped (not just stopping)
        // And we have a reason to discover (lost endpoints or no connections)
        if (!isDiscovering && !isStoppingDiscovery) {
          val hasReasonToDiscover = lostEndpoints.isNotEmpty() || connectedEndpoints.isEmpty()
          if (hasReasonToDiscover) {
            android.util.Log.d("MeshService", "🔄 Restarting discovery (status check)...")
            startDiscovery()
          }
        }
        
        connectionHandler.postDelayed(this, 10000) // 10 seconds
      }
    }, 10000)
  }

  override fun onDestroy() {
    super.onDestroy()
    discoveryHandler.removeCallbacksAndMessages(null)
    connectionHandler.removeCallbacksAndMessages(null)
    reconnectHandler.removeCallbacksAndMessages(null)
    stopAdvertising()
    stopDiscovery()
    connections.stopAllEndpoints()
    connectedEndpoints.clear()
    lostEndpoints.clear()
    isDiscovering = false
    isStoppingDiscovery = false
    pendingDiscoveryStart = false
    android.util.Log.d("MeshService", "MeshService destroyed")
  }
}


