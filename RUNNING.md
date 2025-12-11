# How to Run HimSafeNet Mesh Project

## Prerequisites

Before running the project, ensure you have the following installed:

### Required Software:
1. **Node.js** (version >= 20) - [Download](https://nodejs.org/)
2. **Java Development Kit (JDK)** - Version 17 or higher
3. **Android Studio** - [Download](https://developer.android.com/studio)
4. **React Native CLI** (optional but recommended)

### For Android Development:
- Android SDK (installed via Android Studio)
- Android SDK Platform 36
- Android Emulator or a physical Android device
- Enable USB Debugging on physical device

### For iOS Development (macOS only):
- Xcode (latest version)
- CocoaPods
- iOS Simulator or physical iOS device

---

## Installation Steps

### 1. Install Dependencies

Navigate to the project directory:

```bash
cd HimSafeNetMesh
```

Install Node.js dependencies:

```bash
npm install
```

Or if you prefer Yarn:

```bash
yarn install
```

### 2. Android Setup

#### Install Android Dependencies

Make sure you have:
- Android Studio installed
- Android SDK Platform 36 installed
- Android SDK Build Tools 36.0.0
- An Android Virtual Device (AVD) created, OR a physical device connected

#### Configure Android Environment Variables

Add to your `~/.bashrc`, `~/.zshrc`, or Windows environment variables:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk  # macOS/Linux
# OR
export ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk  # Windows

export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/tools
export PATH=$PATH:$ANDROID_HOME/tools/bin
```

### 3. iOS Setup (macOS only)

Navigate to the iOS directory and install CocoaPods dependencies:

```bash
cd ios
bundle install
bundle exec pod install
cd ..
```

---

## Running the Project

### Option 1: Run on Android (Recommended)

#### Step 1: Start Metro Bundler

Open a terminal in the project root and run:

```bash
npm start
```

Or:

```bash
yarn start
```

This starts the Metro bundler. Keep this terminal running.

#### Step 2: Run on Android

In a **new terminal window**, run:

```bash
npm run android
```

Or:

```bash
yarn android
```

This will:
- Build the Android app
- Install it on your connected device/emulator
- Launch the app

**Note:** Make sure you have:
- An Android emulator running, OR
- A physical Android device connected via USB with USB debugging enabled

### Option 2: Run on iOS (macOS only)

#### Step 1: Start Metro Bundler

```bash
npm start
```

#### Step 2: Run on iOS

In a new terminal:

```bash
npm run ios
```

Or:

```bash
yarn ios
```

---

## Running on Physical Devices

### Android Physical Device

1. **Enable Developer Options** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times

2. **Enable USB Debugging**:
   - Go to Settings → Developer Options
   - Enable "USB Debugging"

3. **Connect device** via USB

4. **Verify connection**:
   ```bash
   adb devices
   ```
   You should see your device listed

5. **Run the app**:
   ```bash
   npm run android
   ```

### iOS Physical Device

1. Open the project in Xcode:
   ```bash
   open ios/HimSafeNetMesh.xcworkspace
   ```

2. Select your device from the device dropdown

3. Configure signing in Xcode (add your Apple Developer account)

4. Click Run or press `Cmd + R`

---

## Important Notes for HimSafeNet Mesh

### Permissions Required

This app requires several permissions that will be requested at runtime:
- **Bluetooth** (for Nearby Connections)
- **Location** (required for Bluetooth scanning on Android)
- **Nearby Wi-Fi Devices** (for mesh networking)
- **Notifications** (for emergency alerts)

**Make sure to grant all permissions when prompted!**

### Testing Mesh Networking

To test the mesh networking functionality:

1. **Run the app on multiple devices** (at least 2)
2. **Grant all permissions** on each device
3. **Wait for connection** - devices should automatically discover and connect
4. **Send an alert** from one device - it should appear on all connected devices

### Troubleshooting Connection Issues

If devices aren't connecting:

1. **Check permissions** - All permissions must be granted
2. **Check Bluetooth** - Ensure Bluetooth is enabled on all devices
3. **Check location** - Location services must be enabled
4. **Check proximity** - Devices should be within Bluetooth range (~10 meters)
5. **Restart the app** - Sometimes a restart helps with initial connection
6. **Check logs** - View the System Log in the app for connection status

---

## Development Commands

### Start Metro Bundler
```bash
npm start
```

### Run on Android
```bash
npm run android
```

### Run on iOS
```bash
npm run ios
```

### Lint Code
```bash
npm run lint
```

### Run Tests
```bash
npm test
```

### Clear Metro Cache
```bash
npm start -- --reset-cache
```

### Clean Android Build
```bash
cd android
./gradlew clean
cd ..
```

---

## Common Issues and Solutions

### Issue: "SDK location not found"
**Solution:** Set `ANDROID_HOME` environment variable (see Android Setup above)

### Issue: "Command not found: react-native"
**Solution:** Install React Native CLI globally:
```bash
npm install -g react-native-cli
```

### Issue: Metro bundler won't start
**Solution:** Clear cache and reinstall:
```bash
npm start -- --reset-cache
rm -rf node_modules
npm install
```

### Issue: App crashes on Android
**Solution:** 
1. Check Android Studio Logcat for errors
2. Ensure all permissions are granted
3. Check that minSdkVersion (24) is supported on your device

### Issue: Devices not connecting
**Solution:**
1. Ensure both devices have all permissions granted
2. Check that Bluetooth and Location are enabled
3. Try restarting both apps
4. Check the System Log in the app for error messages

### Issue: Build fails with Gradle errors
**Solution:**
```bash
cd android
./gradlew clean
cd ..
npm run android
```

---

## Building for Production

### Android APK

```bash
cd android
./gradlew assembleRelease
```

The APK will be at: `android/app/build/outputs/apk/release/app-release.apk`

### Android App Bundle (AAB)

```bash
cd android
./gradlew bundleRelease
```

The AAB will be at: `android/app/build/outputs/bundle/release/app-release.aab`

---

## Additional Resources

- [React Native Documentation](https://reactnative.dev/docs/getting-started)
- [Android Development Setup](https://reactnative.dev/docs/environment-setup)
- [iOS Development Setup](https://reactnative.dev/docs/environment-setup)
- [Troubleshooting Guide](https://reactnative.dev/docs/troubleshooting)

---

## Quick Start (TL;DR)

```bash
# 1. Install dependencies
cd HimSafeNetMesh
npm install

# 2. Start Metro (Terminal 1)
npm start

# 3. Run on Android (Terminal 2)
npm run android

# 4. Grant all permissions when prompted
# 5. Test with multiple devices!
```

---

**Happy Coding! 🚀**

