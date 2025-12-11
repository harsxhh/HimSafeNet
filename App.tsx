/**
 * HimSafeNet Mesh - Offline Emergency Alert System
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { StatusBar, StyleSheet, useColorScheme, View, TextInput, TouchableOpacity, Text, Vibration, NativeEventEmitter, NativeModules, ScrollView, Alert, Animated, ActivityIndicator } from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import { useEffect, useRef, useState } from 'react';

const { Mesh } = NativeModules as { Mesh: { startService: () => Promise<void>; sendAlert: (text: string) => Promise<void>; requestPermissions: () => Promise<void> } };

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <AppContent />
    </SafeAreaProvider>
  );
}

function AppContent() {
  const safeAreaInsets = useSafeAreaInsets();
  const [text, setText] = useState('Emergency alert! Move to higher ground.');
  const [log, setLog] = useState<string[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [peerCount, setPeerCount] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState<'connecting' | 'connected' | 'disconnected'>('disconnected');
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const statusColorAnim = useRef(new Animated.Value(0)).current;
  const scrollViewRef = useRef<ScrollView>(null);

  // Pulse animation for connection status
  useEffect(() => {
    if (isConnected) {
      const pulse = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, {
            toValue: 1.1,
            duration: 1000,
            useNativeDriver: true,
          }),
          Animated.timing(pulseAnim, {
            toValue: 1,
            duration: 1000,
            useNativeDriver: true,
          }),
        ])
      );
      pulse.start();
      return () => pulse.stop();
    }
  }, [isConnected]);

  // Status color animation
  useEffect(() => {
    Animated.timing(statusColorAnim, {
      toValue: isConnected ? 1 : 0,
      duration: 500,
      useNativeDriver: false,
    }).start();
  }, [isConnected]);

  useEffect(() => {
    // Start with permissions and service
    requestPermissionsAndStart();
      
    const emitter = new NativeEventEmitter(NativeModules.Mesh);
    const sub1 = emitter.addListener('AlertReceived', (e: { id: string; text: string; timestamp: number; ttl: number }) => {
      const timestamp = new Date().toLocaleTimeString();
      setLog((l) => [`[${timestamp}] 📨 Received: ${e.text}`, ...l]);
      Vibration.vibrate([200, 100, 200, 100, 200]);
      Alert.alert('🚨 Emergency Alert', e.text, [{ text: 'OK' }]);
      // Auto-scroll to top when new alert arrives
      setTimeout(() => scrollViewRef.current?.scrollTo({ y: 0, animated: true }), 100);
    });
    const sub2 = emitter.addListener('MeshStatus', (e: { message: string }) => {
      const timestamp = new Date().toLocaleTimeString();
      setLog((l) => [`[${timestamp}] ${e.message}`, ...l]);
      
      // Update connection status
      if (e.message.includes('Starting mesh service') || e.message.includes('Requesting permissions')) {
        setConnectionStatus('connecting');
        setIsLoading(true);
      } else if (e.message.includes('Connected to peer') || e.message.includes('Connection accepted')) {
        setIsConnected(true);
        setConnectionStatus('connected');
        setIsLoading(false);
        setPeerCount(prev => prev + 1);
      } else if (e.message.includes('Disconnected from peer') || e.message.includes('Lost peer')) {
        setPeerCount(prev => {
          const newCount = Math.max(0, prev - 1);
          if (newCount === 0) {
            setIsConnected(false);
            setConnectionStatus('disconnected');
          }
          return newCount;
        });
      } else if (e.message.includes('Broadcasting to')) {
        // Don't update connection status from broadcast messages
        // They show recipient count (excluding sender), not actual connections
        // The actual connection status is updated from "Status: X peers connected" messages
        // Just ignore these messages for connection status updates
      } else if (e.message.includes('Status:') && e.message.includes('peers connected')) {
        // Update connection status from actual status messages
        const regex = /Status: (\d+) peers connected/;
        const match = regex.exec(e.message);
        if (match) {
          const count = Number.parseInt(match[1], 10);
          setPeerCount(count);
          setIsConnected(count > 0);
          setConnectionStatus(count > 0 ? 'connected' : 'disconnected');
        }
      } else if (e.message.includes('Reconnecting') || e.message.includes('Retrying')) {
        setConnectionStatus('connecting');
      } else if (e.message.includes('Started advertising') || e.message.includes('Started discovery')) {
        setIsLoading(false);
      }
    });
    return () => {
      sub1.remove();
      sub2.remove();
    };
  }, []);

  const requestPermissionsAndStart = async () => {
    try {
      setIsLoading(true);
      setConnectionStatus('connecting');
      const timestamp = new Date().toLocaleTimeString();
      setLog((l) => [`[${timestamp}] 🔐 Requesting permissions...`, ...l]);
      
      // Request permissions through the native module
      await Mesh.requestPermissions();
      
      // Wait a moment for permissions to be processed
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      setLog((l) => [`[${new Date().toLocaleTimeString()}] 🚀 Starting mesh service...`, ...l]);
      await Mesh.startService();
      setLog((l) => [`[${new Date().toLocaleTimeString()}] ✅ Mesh service started`, ...l]);
    } catch (err: any) {
      setIsLoading(false);
      setConnectionStatus('disconnected');
      const timestamp = new Date().toLocaleTimeString();
      setLog((l) => [`[${timestamp}] ❌ Error: ${err.message || 'Unknown error'}`, ...l]);
    }
  };

  const send = () => {
    if (!text.trim()) {
      Alert.alert('Error', 'Please enter an alert message');
      return;
    }
    const timestamp = new Date().toLocaleTimeString();
    Mesh.sendAlert(text).then(() => {
      setLog((l) => [`[${timestamp}] 📤 Sent: ${text}`, ...l]);
      Vibration.vibrate([100, 50, 100]);
      // Auto-scroll to top when sending
      setTimeout(() => scrollViewRef.current?.scrollTo({ y: 0, animated: true }), 100);
    }).catch((err: any) => {
      setLog((l) => [`[${new Date().toLocaleTimeString()}] ❌ Send failed: ${err.message || 'Unknown error'}`, ...l]);
    });
  };

  const retryPermissions = () => {
    const timestamp = new Date().toLocaleTimeString();
    setLog((l) => [`[${timestamp}] 🔄 Retrying permissions...`, ...l]);
    requestPermissionsAndStart();
  };

  const clearLog = () => {
    Alert.alert(
      'Clear Log',
      'Are you sure you want to clear all log entries?',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Clear', style: 'destructive', onPress: () => setLog([]) },
      ]
    );
  };

  const getStatusColor = () => {
    return statusColorAnim.interpolate({
      inputRange: [0, 1],
      outputRange: ['#F44336', '#4CAF50'],
    });
  };

  const getStatusText = () => {
    if (isLoading || connectionStatus === 'connecting') {
      return '🟡 Connecting...';
    }
    if (isConnected && peerCount > 0) {
      return `🟢 Connected (${peerCount} ${peerCount === 1 ? 'peer' : 'peers'})`;
    }
    return '🔴 Disconnected';
  };

  return (
    <View style={[styles.container, { paddingTop: safeAreaInsets.top }]}>
      {/* Header with Gradient Effect */}
      <View style={styles.header}>
        <View style={styles.headerContent}>
          <Text style={styles.title}>🚨 HimSafeNet Mesh</Text>
          <Text style={styles.subtitle}>Offline Emergency Alert System</Text>
          
          {/* Status Indicator with Animation */}
          <Animated.View 
            style={[
              styles.statusContainer, 
              { 
                backgroundColor: getStatusColor(),
              }
            ]}
          >
            <Animated.View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                transform: [{ scale: pulseAnim }],
              }}
            >
              {isLoading && (
                <ActivityIndicator size="small" color="white" style={styles.statusLoader} />
              )}
              <Text style={styles.statusText}>
                {getStatusText()}
              </Text>
            </Animated.View>
          </Animated.View>
        </View>
      </View>

      {/* Alert Input Card */}
      <View style={styles.inputContainer}>
        <View style={styles.inputHeader}>
          <Text style={styles.inputLabel}>📢 Emergency Alert Message</Text>
          <View style={[styles.charCountContainer, { opacity: text.length > 0 ? 1 : 0.5 }]}>
            <Text style={styles.charCount}>{text.length} characters</Text>
          </View>
        </View>
        <TextInput
          value={text}
          onChangeText={setText}
          style={styles.textInput}
          placeholder="Type your emergency alert message here..."
          placeholderTextColor="#999"
          multiline
          numberOfLines={3}
          maxLength={500}
        />
        <TouchableOpacity 
          style={[
            styles.sendButton, 
            { 
              opacity: text.trim() ? 1 : 0.5,
              shadowColor: text.trim() ? '#F44336' : '#999',
            }
          ]} 
          onPress={send}
          disabled={!text.trim()}
        >
          <Text style={styles.sendButtonText}>🚨 SEND ALERT</Text>
        </TouchableOpacity>
      </View>

      {/* Control Buttons */}
      <View style={styles.controlContainer}>
        <TouchableOpacity 
          style={[styles.controlButton, styles.retryButton]} 
          onPress={retryPermissions}
          disabled={isLoading}
        >
          <Text style={styles.controlButtonText}>
            {isLoading ? '⏳ Connecting...' : '🔄 Retry Connection'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity 
          style={[styles.controlButton, styles.clearButton]} 
          onPress={clearLog}
          disabled={log.length === 0}
        >
          <Text style={[styles.controlButtonText, { opacity: log.length === 0 ? 0.5 : 1 }]}>
            🗑️ Clear Log
          </Text>
        </TouchableOpacity>
      </View>

      {/* Log Container */}
      <View style={styles.logContainer}>
        <View style={styles.logHeader}>
          <Text style={styles.logTitle}>📋 System Log</Text>
          <View style={styles.logBadge}>
            <Text style={styles.logBadgeText}>{log.length}</Text>
          </View>
        </View>
        {log.length === 0 ? (
          <View style={styles.emptyLogContainer}>
            <Text style={styles.emptyLogText}>No log entries yet</Text>
            <Text style={styles.emptyLogSubtext}>System events and alerts will appear here</Text>
          </View>
        ) : (
          <ScrollView 
            ref={scrollViewRef}
            style={styles.logScrollView} 
            showsVerticalScrollIndicator={true}
            contentContainerStyle={styles.logContent}
          >
            {log.map((item, idx) => (
              <View key={`${idx}-${item}`} style={styles.logItem}>
                <Text style={styles.logText}>{item}</Text>
              </View>
            ))}
          </ScrollView>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f0f4f8',
  },
  header: {
    backgroundColor: '#1e3a8a',
    paddingBottom: 20,
    borderBottomLeftRadius: 25,
    borderBottomRightRadius: 25,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 8,
  },
  headerContent: {
    padding: 20,
    paddingTop: 15,
    alignItems: 'center',
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: 'white',
    marginBottom: 6,
    textShadowColor: 'rgba(0, 0, 0, 0.3)',
    textShadowOffset: { width: 0, height: 2 },
    textShadowRadius: 4,
  },
  subtitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.9)',
    marginBottom: 18,
    fontWeight: '500',
  },
  statusContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 25,
    minWidth: 220,
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    elevation: 4,
  },
  statusLoader: {
    marginRight: 8,
  },
  statusText: {
    color: 'white',
    fontWeight: 'bold',
    fontSize: 15,
    letterSpacing: 0.5,
  },
  inputContainer: {
    margin: 16,
    backgroundColor: 'white',
    borderRadius: 16,
    padding: 18,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.15,
    shadowRadius: 6,
    elevation: 5,
  },
  inputHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  inputLabel: {
    fontSize: 17,
    fontWeight: '700',
    color: '#1e3a8a',
    flex: 1,
  },
  charCountContainer: {
    backgroundColor: '#e0e7ff',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  charCount: {
    fontSize: 11,
    color: '#6366f1',
    fontWeight: '600',
  },
  textInput: {
    borderWidth: 2,
    borderColor: '#e5e7eb',
    borderRadius: 12,
    padding: 14,
    fontSize: 16,
    backgroundColor: '#fafafa',
    textAlignVertical: 'top',
    minHeight: 100,
    color: '#1f2937',
    fontFamily: 'System',
  },
  sendButton: {
    backgroundColor: '#F44336',
    paddingVertical: 16,
    paddingHorizontal: 24,
    borderRadius: 12,
    marginTop: 16,
    alignItems: 'center',
    shadowColor: '#F44336',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.4,
    shadowRadius: 8,
    elevation: 6,
  },
  sendButtonText: {
    color: 'white',
    fontSize: 18,
    fontWeight: 'bold',
    letterSpacing: 1,
  },
  controlContainer: {
    flexDirection: 'row',
    marginHorizontal: 16,
    marginBottom: 16,
    gap: 12,
  },
  controlButton: {
    flex: 1,
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  retryButton: {
    backgroundColor: '#FF9800',
  },
  clearButton: {
    backgroundColor: '#6b7280',
  },
  controlButtonText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '700',
  },
  logContainer: {
    flex: 1,
    margin: 16,
    marginTop: 0,
    backgroundColor: 'white',
    borderRadius: 16,
    padding: 18,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 3 },
    shadowOpacity: 0.15,
    shadowRadius: 6,
    elevation: 5,
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  logTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: '#1e3a8a',
  },
  logBadge: {
    backgroundColor: '#3b82f6',
    borderRadius: 12,
    paddingHorizontal: 10,
    paddingVertical: 4,
    minWidth: 30,
    alignItems: 'center',
  },
  logBadgeText: {
    color: 'white',
    fontSize: 12,
    fontWeight: 'bold',
  },
  logScrollView: {
    flex: 1,
  },
  logContent: {
    paddingBottom: 10,
  },
  logItem: {
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderBottomWidth: 1,
    borderBottomColor: '#f3f4f6',
    backgroundColor: '#fafafa',
    marginBottom: 6,
    borderRadius: 8,
  },
  logText: {
    fontSize: 13,
    color: '#374151',
    fontFamily: 'monospace',
    lineHeight: 18,
  },
  emptyLogContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 40,
  },
  emptyLogText: {
    fontSize: 16,
    color: '#9ca3af',
    fontWeight: '600',
    marginBottom: 8,
  },
  emptyLogSubtext: {
    fontSize: 13,
    color: '#d1d5db',
    textAlign: 'center',
  },
});

export default App;
