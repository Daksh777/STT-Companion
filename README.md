# Simple Time Tracker Companion

A local LAN companion app for [Android Simple Time Tracker](https://github.com/Razeeman/Android-SimpleTimeTracker). 

This companion app runs an embedded Ktor HTTP server directly on your Android device. It acts as a bridge, allowing you to view and control your STT trackers from a web browser on your computer via your local Wi-Fi network.

## Features

- **Embedded Web Dashboard**: Access a Material You styled dashboard directly from your browser. No separate frontend hosting required.
- **Start / Stop**: Start and stop activities from the web dashboard.
- **Live Updates**: Uses Server-Sent Events (SSE) to update the dashboard instantly when an activity is started or stopped (even if triggered from your phone/watch).
- **Secure on LAN**: Generates a random Auth Token on first launch to secure the endpoints.
- **Auto Start**: Runs silently as an Android Foreground Service and automatically restarts on device boot.

## Setup Instructions

### 1. Simple Time Tracker Setup
This companion app relies on the Broadcast Intents introduced in STT.
1. Install Simple Time Tracker on your Android device.
2. Go to **Settings > Additional > Automated Tracking**.
3. Toggle on **Answer queries**.

### 2. Companion App Setup
1. Clone this repository.
2. Build and install via ADB:
   ```bash
   ./gradlew installDebug
   ```
3. Open **STT Companion** on your phone.
4. Tap **Start Server**.
5. Note the **Auth Token** and **Local IP Address** displayed on the screen.

### 3. Usage
1. Open your computer's web browser.
2. Navigate to `http://<YOUR_PHONE_IP>:8080`.
3. Enter your Auth Token.
4. You can now view and manage your time trackers directly from your PC!

## Tech Stack
- **Kotlin** (Android Foreground Service, Broadcast Receivers)
- **Ktor** (Embedded HTTP Server, Server-Sent Events)
- **Jetpack Compose** (Android UI)
- **HTML / Vanilla JS** (Embedded Web Dashboard styled with Material 3 CSS)
