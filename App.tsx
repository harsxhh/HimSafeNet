/**
 * HimSafeNet Mesh - Offline Emergency Alert System
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { StatusBar, StyleSheet, useColorScheme, View, TextInput, TouchableOpacity, FlatList, Text, Vibration, NativeEventEmitter, NativeModules, ScrollView, Alert } from 'react-native';
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

  useEffect(() => {
    // Start with permissions and service
    requestPermissionsAndStart();
      
    const emitter = new NativeEventEmitter(NativeModules.Mesh);
    const sub1 = emitter.addListener('AlertReceived', (e: { id: string; text: string; timestamp: number; ttl: number }) => {
      setLog((l) => [`📨 Received: ${e.text}`, ...l]);
      Vibration.vibrate([200, 100, 200, 100, 200]);
      Alert.alert('🚨 Emergency Alert', e.text, [{ text: 'OK' }]);
    });
    const sub2 = emitter.addListener('MeshStatus', (e: { message: string }) => {
      setLog((l) => [e.message, ...l]);
      
      // Update connection status
      if (e.message.includes('Connected to peer')) {
        setIsConnected(true);
        setPeerCount(prev => prev + 1);
      } else if (e.message.includes('Disconnected from peer')) {
        setPeerCount(prev => Math.max(0, prev - 1));
        if (peerCount <= 1) setIsConnected(false);
      } else if (e.message.includes('Broadcasting to')) {
        const match = e.message.match(/Broadcasting to (\d+) peers/);
        if (match) {
          const count = parseInt(match[1]);
          setPeerCount(count);
          setIsConnected(count > 0);
        }
      }
    });
    return () => {
      sub1.remove();
      sub2.remove();
    };
  }, [peerCount]);

  const requestPermissionsAndStart = async () => {
    try {
      setLog((l) => ['🔐 Requesting permissions...', ...l]);
      
      // Request permissions through the native module
      await Mesh.requestPermissions();
      
      // Wait a moment for permissions to be processed
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      setLog((l) => ['🚀 Starting mesh service...', ...l]);
      await Mesh.startService();
      setLog((l) => ['✅ Mesh service started', ...l]);
    } catch (err) {
      setLog((l) => [`❌ Error: ${err.message}`, ...l]);
    }
  };

  const send = () => {
    if (!text.trim()) {
      Alert.alert('Error', 'Please enter an alert message');
      return;
    }
    Mesh.sendAlert(text).then(() => {
      setLog((l) => [`📤 Sent: ${text}`, ...l]);
      Vibration.vibrate([100, 50, 100]);
    }).catch(err => {
      setLog((l) => [`❌ Send failed: ${err.message}`, ...l]);
    });
  };

  const retryPermissions = () => {
    setLog((l) => ['🔄 Retrying permissions...', ...l]);
    requestPermissionsAndStart();
  };

  const clearLog = () => {
    setLog([]);
  };

  return (
    <View style={[styles.container, { paddingTop: safeAreaInsets.top }]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>🚨 HimSafeNet Mesh</Text>
        <Text style={styles.subtitle}>Offline Emergency Alert System</Text>
        
        {/* Status Indicator */}
        <View style={[styles.statusContainer, { backgroundColor: isConnected ? '#4CAF50' : '#F44336' }]}>
          <Text style={styles.statusText}>
            {isConnected ? `🟢 Connected (${peerCount} peers)` : '🔴 Disconnected'}
          </Text>
        </View>
      </View>

      {/* Alert Input */}
      <View style={styles.inputContainer}>
        <Text style={styles.inputLabel}>Emergency Alert Message:</Text>
        <TextInput
          value={text}
          onChangeText={setText}
          style={styles.textInput}
          placeholder="Type your emergency alert message here..."
          multiline
          numberOfLines={3}
        />
        <TouchableOpacity style={[styles.sendButton, { opacity: text.trim() ? 1 : 0.5 }]} onPress={send}>
          <Text style={styles.sendButtonText}>🚨 SEND ALERT</Text>
        </TouchableOpacity>
      </View>

      {/* Control Buttons */}
      <View style={styles.controlContainer}>
        <TouchableOpacity style={styles.retryButton} onPress={retryPermissions}>
          <Text style={styles.retryButtonText}>🔄 Retry Permissions</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.clearButton} onPress={clearLog}>
          <Text style={styles.clearButtonText}>🗑️ Clear Log</Text>
        </TouchableOpacity>
      </View>

      {/* Log */}
      <View style={styles.logContainer}>
        <Text style={styles.logTitle}>📋 System Log:</Text>
        <ScrollView style={styles.logScrollView} showsVerticalScrollIndicator={true}>
          {log.map((item, idx) => (
            <View key={`${idx}-${item}`} style={styles.logItem}>
              <Text style={styles.logText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    backgroundColor: '#2196F3',
    padding: 20,
    paddingBottom: 15,
    alignItems: 'center',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: 'white',
    marginBottom: 5,
  },
  subtitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.8)',
    marginBottom: 15,
  },
  statusContainer: {
    paddingHorizontal: 15,
    paddingVertical: 8,
    borderRadius: 20,
    minWidth: 200,
    alignItems: 'center',
  },
  statusText: {
    color: 'white',
    fontWeight: 'bold',
    fontSize: 14,
  },
  inputContainer: {
    margin: 15,
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 15,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  inputLabel: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 10,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 12,
    fontSize: 16,
    backgroundColor: '#fafafa',
    textAlignVertical: 'top',
    minHeight: 80,
  },
  sendButton: {
    backgroundColor: '#F44336',
    paddingVertical: 15,
    paddingHorizontal: 20,
    borderRadius: 8,
    marginTop: 15,
    alignItems: 'center',
  },
  sendButtonText: {
    color: 'white',
    fontSize: 18,
    fontWeight: 'bold',
  },
  controlContainer: {
    flexDirection: 'row',
    marginHorizontal: 15,
    marginBottom: 15,
    gap: 10,
  },
  retryButton: {
    flex: 1,
    backgroundColor: '#FF9800',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  retryButtonText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '600',
  },
  clearButton: {
    flex: 1,
    backgroundColor: '#9E9E9E',
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  clearButtonText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '600',
  },
  logContainer: {
    flex: 1,
    margin: 15,
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 15,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
    elevation: 3,
  },
  logTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 10,
  },
  logScrollView: {
    flex: 1,
  },
  logItem: {
    paddingVertical: 6,
    paddingHorizontal: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  logText: {
    fontSize: 13,
    color: '#555',
    fontFamily: 'monospace',
  },
});

export default App;
