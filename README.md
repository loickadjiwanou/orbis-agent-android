# Orbis Agent Android

Android monitoring agent — client component of the Orbis project.

The agent installs on an Android device, automatically registers with the Orbis backend via MQTT, then continuously publishes system metrics (battery, CPU, RAM, storage, network) and responds to remote commands sent from the dashboard.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Requirements](#requirements)
3. [Build & Installation](#build--installation)
4. [Configuration — Onboarding](#configuration--onboarding)
5. [Features](#features)
6. [Supported Commands](#supported-commands)
7. [Project Structure](#project-structure)
8. [MQTT Flow](#mqtt-flow)
9. [Local Storage](#local-storage)
10. [Resilience & Reconnection](#resilience--reconnection)
11. [Auto-start](#auto-start)
12. [User Action Behavior](#user-action-behavior)
13. [Development](#development)

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                 Android Device                  │
│                                                 │
│  ┌──────────────┐     ┌───────────────────────┐ │
│  │ OrbisService │────▶│   MqttManager         │ │
│  │  (foreground)│     │  (Paho MQTT v3)       │ │
│  │              │     │  - connect/reconnect   │ │
│  │  heartbeat   │     │  - publish / subscribe │ │
│  │  30 s loop   │     │  - offline buffering   │ │
│  │  logs 60 s   │     └───────────┬───────────┘ │
│  └──────┬───────┘                 │             │
│         │                         │ MQTT        │
│  ┌──────▼───────┐     ┌───────────▼───────────┐ │
│  │DeviceData    │     │  MqttMessageHandler   │ │
│  │Collector     │     │  CommandExecutor      │ │
│  │- battery     │     │  - collect_now        │ │
│  │- CPU /proc   │     │  - scan_network       │ │
│  │- RAM         │     │  - restart_service    │ │
│  │- storage     │     │  - agent_update       │ │
│  │- network     │     │  - get_info           │ │
│  └──────────────┘     └───────────────────────┘ │
│                                                 │
│  ┌──────────────┐     ┌───────────────────────┐ │
│  │OrbisPrefs    │     │  MessageRepository    │ │
│  │(encrypted)   │     │  (Room — offline buf) │ │
│  │- device_id   │     │  - pending_messages   │ │
│  │- token       │     └───────────────────────┘ │
│  │- broker_url  │                               │
│  └──────────────┘                               │
└─────────────────────────────────────────────────┘
                         │ MQTT TCP/TLS
              ┌──────────▼──────────┐
              │    EMQX Broker      │
              │    port 1883/8883   │
              └──────────┬──────────┘
                         │
              ┌──────────▼──────────┐
              │  Backend FastAPI    │
              │  port 8000          │
              └─────────────────────┘
```

---

## Requirements

### Android Device

| Requirement     | Value                              |
|-----------------|------------------------------------|
| Android version | ≥ 8.0 (API 26 — Oreo)             |
| Network         | Wi-Fi or mobile data               |
| Broker access   | Same network or reachable IP       |

### Build Environment

| Tool           | Minimum version          |
|----------------|--------------------------|
| Android Studio | Hedgehog (2023)          |
| JDK            | 17                       |
| Kotlin         | 1.9.x                    |
| Gradle         | 8.6                      |
| Android SDK    | compileSdk 34, minSdk 26 |

### Server-side Infrastructure

- EMQX 5.x with `password_based:built_in_database` authenticator configured
- MQTT `register` user created with the backend `MQTT_REGISTER_SECRET`
- Orbis backend running and connected to the broker

---

## Build & Installation

### 1. Clone and configure

```bash
cd orbis-agent-android

# Create local.properties if missing
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

### 2. Debug build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### 3. Release build (signed)

```bash
./gradlew assembleRelease
```

> Configure the keystore in `app/build.gradle.kts` before building a release.

### 4. Install on device

```bash
# Via ADB (USB or Wi-Fi ADB)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verify installation
adb shell pm list packages | grep orbis
# → package:com.orbis.agent
```

### 5. Launch via ADB (optional)

```bash
adb shell am start -n com.orbis.agent/.ui.MainActivity
```

---

## CI/CD — GitHub Actions

The workflow `.github/workflows/build-orbis-agent-android.yml` automatically builds and signs the release APK on every push.

### Required Secrets

Set these once in **GitHub repo → Settings → Secrets and variables → Actions → New repository secret** :

| Secret name        | Value                                                                 |
|--------------------|-----------------------------------------------------------------------|
| `RELEASE_KEYSTORE` | Base64-encoded `.keystore` file: `base64 release.keystore`           |
| `KEYSTORE_PASSWORD`| Password entered during `keytool -genkey`                             |
| `KEY_ALIAS`        | Alias used during `keytool -genkey` (e.g. `orbis`)                   |
| `KEY_PASSWORD`     | Key password entered during `keytool -genkey`                         |

### Generate the keystore (once)

```bash
keytool -genkey -v -keystore release.keystore -alias orbis \
  -keyalg RSA -keysize 4096 -validity 10000

# Encode it for GitHub Secrets
base64 release.keystore
```

> `release.keystore` is listed in `.gitignore` — it will never be committed.

### Manual build trigger

1. Go to **GitHub repo → Actions → "Build Agent Android"**
2. Click **Run workflow**
3. The signed APK and its `apk.sha256` will be available in the **Artifacts** section of the run

### Create an official release

```bash
git tag v1.0.0
git push origin v1.0.0
```

This triggers the build automatically and creates a **GitHub Release** with the signed APK and its SHA-256 checksum attached. The SHA-256 value is required in the `agent_update` command payload for OTA updates.

---

## Configuration — Onboarding

On first launch, the app displays the onboarding screen.

### Fields

| Field           | Example                   | Description                                    |
|-----------------|---------------------------|------------------------------------------------|
| Broker URL      | `tcp://192.168.1.59:1883` | EMQX broker address (TCP or SSL)               |
| Register secret | `orbis_register_secret`   | Shared secret defined in the backend `.env`    |

### Supported Protocols

| Protocol | URL prefix | Default port |
|----------|------------|--------------|
| TCP      | `tcp://`   | 1883         |
| TLS/SSL  | `ssl://`   | 8883         |

### Onboarding Flow

```
App                         Broker                     Backend
 |                             |                           |
 |── connect (user=register) ──▶|                           |
 |── subscribe devices/<id>/register_ack ──▶|              |
 |── publish devices/register ──▶|──────── MQTT ──────────▶|
 |                             |            handle_register |
 |                             |◀──── register_ack (token) ─|
 |◀── register_ack ────────────|                           |
 |    {status:"ok", token:"…"} |                           |
 |── disconnect ──▶|           |                           |
 |── connect (user=<device_id>, pass=<token>) ──▶|         |
 |── subscribe devices/<id>/commands ──▶|                  |
```

Credentials are stored encrypted in `EncryptedSharedPreferences` (AES256-GCM).

### Reset Onboarding

To start fresh without reinstalling:

```bash
adb shell pm clear com.orbis.agent
```

---

## Features

### Automatic Heartbeat (30 seconds)

Every 30 seconds, the agent publishes a full payload to:

```
Topic  : devices/<device_id>/status
QoS    : 1
Retain : true
```

**Payload:**

```json
{
  "device_id": "cb6ef72e-451e-4cd4-854a-ccba0f0288b1",
  "timestamp": "2026-05-06T21:00:00.000Z",
  "status": "online",
  "version": "1.0.0",
  "uptime_sec": 3600,
  "hostname": "Pixel 7",
  "plateforme": "android",
  "os_version": "Android 14 (API 34)",
  "architecture": "arm64-v8a",
  "cpu_percent": 12.5,
  "ram_percent": 68.3,
  "storage_percent": 45.1,
  "battery_level": 87,
  "battery_charging": false,
  "network_type": "WIFI"
}
```

### Log Collection (60 seconds)

Every 60 seconds, a metrics log entry is published to:

```
Topic  : devices/<device_id>/logs
QoS    : 1
Retain : false
```

**Payload:**

```json
{
  "device_id": "cb6ef72e-...",
  "timestamp": "2026-05-06T21:01:00.000Z",
  "level": "INFO",
  "source": "DeviceDataCollector",
  "message": "Device metrics collected",
  "metadata": {
    "cpu_percent": "12.5",
    "ram_percent": "68.3",
    "storage_percent": "45.1",
    "battery_level": "87",
    "battery_charging": "false",
    "network_type": "WIFI",
    "uptime_sec": "3600"
  }
}
```

### Last Will Testament

On an abrupt disconnection, the broker automatically publishes:

```
Topic  : devices/<device_id>/status
Payload: {"device_id":"…","status":"offline","uptime_sec":0,…}
Retain : true
```

---

## Supported Commands

Commands are sent by the backend via:

```
Topic : devices/<device_id>/commands
QoS   : 1
```

Results are published to:

```
Topic : devices/<device_id>/results
QoS   : 1
```

### Command Lifecycle

```
pending → sent → acknowledged → executing → success | failed
```

Each transition is published to the `results` topic as it happens.

---

### `get_info`

Returns the complete device information.

**Command payload:**
```json
{"type": "get_info", "payload": {}, "timeout_sec": 30}
```

**Result (`output`):**
```
device_id: cb6ef72e-...
hostname: Pixel 7
plateforme: android
os_version: Android 14 (API 34)
architecture: arm64-v8a
version: 1.0.0
uptime_sec: 3600
cpu_percent: 12.5
ram_percent: 68.3
storage_percent: 45.1
battery_level: 87
battery_charging: false
network_type: WIFI
```

---

### `collect_now`

Forces an immediate heartbeat (outside the 30-second cycle).

**Command payload:**
```json
{"type": "collect_now", "payload": {}, "timeout_sec": 30}
```

**Result (`output`):** `"Heartbeat collected and published"`

---

### `restart_service`

Stops and restarts `OrbisService`.

**Command payload:**
```json
{"type": "restart_service", "payload": {}, "timeout_sec": 30}
```

**Result (`output`):** `"OrbisService restart initiated"`

> The `success` result is published **before** stopping the service to guarantee delivery.
> The service automatically restarts 2 seconds later via AlarmManager.

---

### `scan_network`

Scans the local subnet to detect active hosts and their open ports.

**Command payload:**
```json
{"type": "scan_network", "payload": {}, "timeout_sec": 180}
```

**Scan parameters:**
- Range: `<subnet>.1` to `<subnet>.254` (subnet of the active interface)
- Timeout per host: 500 ms (ICMP reachable)
- Scanned ports: `22, 80, 443, 8080, 3000, 5000, 8443`

**Result (`output`):**
```
192.168.1.1: ports 80, 5000
192.168.1.59: reachable (no open ports)
192.168.1.74: reachable (no open ports)
```

> Typical duration: 1 to 3 minutes. Always send with `timeout_sec ≥ 180`.

---

### `agent_update`

Downloads a new APK version and launches the installation.

**Command payload:**
```json
{
  "type": "agent_update",
  "payload": {
    "url": "https://example.com/orbis-agent-1.1.0.apk",
    "sha256": "e3b0c44298fc1c149afb…",
    "version": "1.1.0",
    "changelog": "Bug fixes"
  },
  "timeout_sec": 300
}
```

**Internal steps:**
1. APK download via OkHttp (read timeout: 120 s)
2. SHA-256 verification — **mandatory**, update is rejected if `sha256` is missing or does not match
3. Installation intent launched via `FileProvider`
4. After installation, `PackageReceiver` restarts `OrbisService`

**Result (`output`):** `"APK downloaded and install prompt launched"`

---

## Project Structure

```
orbis-agent-android/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/orbis/agent/
│   │   │   ├── OrbisApplication.kt         # Entry point — Hilt + WorkManager
│   │   │   ├── collector/
│   │   │   │   └── DeviceDataCollector.kt  # System metrics (CPU, RAM, battery…)
│   │   │   ├── db/
│   │   │   │   ├── LocalDatabase.kt        # Room database
│   │   │   │   ├── MessageDao.kt           # Offline buffer DAO
│   │   │   │   └── MessageEntity.kt        # Room entity
│   │   │   ├── di/
│   │   │   │   └── AppModule.kt            # Hilt module (DB, DAO)
│   │   │   ├── executor/
│   │   │   │   └── CommandExecutor.kt      # MQTT command execution
│   │   │   ├── model/
│   │   │   │   ├── Command.kt              # Received command
│   │   │   │   ├── CommandResult.kt        # Result to publish
│   │   │   │   ├── DeviceStatus.kt         # Heartbeat payload
│   │   │   │   └── LogEntry.kt             # Log payload
│   │   │   ├── mqtt/
│   │   │   │   ├── MqttManager.kt          # MQTT client (connect, pub, sub)
│   │   │   │   └── MqttMessageHandler.kt   # Incoming command dispatch
│   │   │   ├── prefs/
│   │   │   │   └── OrbisPreferences.kt     # Encrypted storage (device_id, token…)
│   │   │   ├── receiver/
│   │   │   │   ├── BootReceiver.kt         # Auto-start on boot
│   │   │   │   └── PackageReceiver.kt      # Restart after APK update
│   │   │   ├── repository/
│   │   │   │   ├── DeviceRepository.kt     # Onboarding and device identity
│   │   │   │   └── MessageRepository.kt    # Room offline buffer
│   │   │   ├── service/
│   │   │   │   └── OrbisService.kt         # Main foreground service
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt         # Main screen (status)
│   │   │   │   ├── MainViewModel.kt        # ViewModel observing MqttManager
│   │   │   │   ├── OnboardingActivity.kt   # Broker URL + secret input
│   │   │   │   └── BatteryOptimizationActivity.kt  # Per-manufacturer guide
│   │   │   └── worker/
│   │   │       ├── HeartbeatWorker.kt      # Backup heartbeat (15 min)
│   │   │       └── ServiceWatchdogWorker.kt# Service watchdog (15 min)
│   │   ├── res/
│   │   │   ├── drawable/                   # Icons and drawables
│   │   │   ├── layout/                     # Activity layouts
│   │   │   ├── mipmap-anydpi-v26/          # Adaptive icons (API 26+)
│   │   │   ├── values/
│   │   │   │   ├── colors.xml              # Orbis color palette
│   │   │   │   └── strings.xml             # UI labels
│   │   │   └── xml/
│   │   │       └── file_paths.xml          # FileProvider paths (agent_update)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── local.properties                        # sdk.dir (gitignored)
└── README.md
```

---

## MQTT Flow

### Topics Published by the Agent

| Topic                          | QoS | Retain | Frequency           | Description              |
|--------------------------------|-----|--------|---------------------|--------------------------|
| `devices/<id>/status`          | 1   | true   | 30 s + on demand    | Heartbeat (DeviceStatus) |
| `devices/<id>/logs`            | 1   | false  | 60 s                | Metrics log entry        |
| `devices/<id>/results`         | 1   | false  | Per command         | Command result           |
| `devices/register`             | 1   | false  | Once (onboarding)   | Registration payload     |

### Topics Subscribed by the Agent

| Topic                       | QoS | Description                        |
|-----------------------------|-----|------------------------------------|
| `devices/<id>/commands`     | 1   | Commands from the backend          |
| `devices/<id>/register_ack` | 1   | Registration response (temporary)  |

### Command Result Format

```json
{
  "command_id": "548d6b7a-f70f-4a08-a2e0-3208e41e0abe",
  "device_id": "cb6ef72e-451e-4cd4-854a-ccba0f0288b1",
  "statut": "success",
  "output": "...",
  "error": "",
  "exit_code": 0,
  "timestamp": "2026-05-06T21:36:17.000Z"
}
```

> The field is named `statut` (French) per the SESSION_0 interface contract.

---

## Local Storage

### EncryptedSharedPreferences

File: `orbis_secure_prefs`  
Encryption: AES256_SIV (key) + AES256_GCM (value)

| Key               | Type   | Description                                     |
|-------------------|--------|-------------------------------------------------|
| `device_id`       | String | UUID assigned during onboarding                 |
| `token`           | String | MQTT token received in the register_ack         |
| `broker_url`      | String | Broker URL (default: tcp://localhost:1883)      |
| `register_secret` | String | Shared secret (default: orbis_register_secret)  |

> This data is **not accessible via ADB**. A reset requires `pm clear` or uninstallation.

### Room Database

Database: `orbis_local.db`  
Table: `pending_messages`

| Column      | Type    | Description                           |
|-------------|---------|---------------------------------------|
| `id`        | Long    | Auto-increment primary key (FIFO)     |
| `topic`     | String  | Target MQTT topic                     |
| `payload`   | String  | JSON payload                          |
| `qos`       | Int     | QoS level (0, 1 or 2)                |
| `retained`  | Boolean | Retain flag                           |
| `createdAt` | Long    | Creation timestamp (ms)               |

Messages are flushed in batches of 50 on each reconnection.

---

## Resilience & Reconnection

### Reconnection Strategy

The agent uses automatic exponential backoff:

| Attempt | Delay          |
|---------|----------------|
| 1       | 1 s            |
| 2       | 2 s            |
| 3       | 4 s            |
| 4       | 8 s            |
| …       | …              |
| ≥ 6     | 60 s (maximum) |

On each successful reconnection:
1. Re-subscribe to all registered topics
2. Flush offline buffer (Room → MQTT, in batches of 50)

### Connection Generation

Each `MqttAsyncClient` is assigned a generation number. Callbacks from a replaced client are automatically discarded, preventing race conditions during rapid reconnections.

### Offline Buffering

When the broker is unreachable, messages are saved locally in Room and resent as soon as the connection is restored, in creation order (FIFO).

---

## Auto-start

### On Device Boot

`BootReceiver` listens for `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED`. The service starts automatically after system boot with no user interaction.

### WorkManager Watchdog

`ServiceWatchdogWorker` runs every 15 minutes to verify that `OrbisService` is active and restarts it if needed.

### AlarmManager Restart

`OrbisService.onDestroy()` schedules a restart via `AlarmManager.setExact()` with a 2-second delay, ensuring service continuity even if the system destroys it.

### Battery Optimization

On some manufacturers (MIUI, Samsung, OnePlus, OPPO, Huawei), battery restrictions can kill background services. `BatteryOptimizationActivity` guides the user to disable these restrictions, with manufacturer-specific instructions.

---

## User Action Behavior

### Closing the App (swipe from recents)

The service **keeps running**. The manifest declares `stopWithTask=false` — removing the task does not affect `OrbisService`.

If the Android system kills the service anyway (manufacturer battery optimization):
- `onDestroy()` → AlarmManager restarts the service in 2 seconds
- `ServiceWatchdogWorker` (WorkManager, 15 min) restarts it if it remains inactive

Only a **Force Stop** from *Settings → Apps → Orbis Agent* can durably stop it — it also cancels the AlarmManager on some devices.

### Uninstalling the App

| Element        | Behavior                                                                                  |
|----------------|-------------------------------------------------------------------------------------------|
| Service        | Killed immediately                                                                        |
| Local data     | Erased (EncryptedSharedPreferences + Room DB) — `device_id` and `token` lost             |
| MongoDB        | Device remains with `statut: online`, goes `offline` after 5 min (inactivity watcher)    |
| EMQX           | MQTT account becomes orphaned — must be removed manually via `DELETE /devices/{id}`       |
| Reinstallation | Generates a **new** `device_id` — the old one remains in MongoDB as a ghost entry         |

> Before uninstalling, call `DELETE /devices/{device_id}` on the backend to properly revoke the EMQX account and mark the device as revoked in the database.

### Summary

| Action        | Service                    | MongoDB                  | EMQX             |
|---------------|----------------------------|--------------------------|------------------|
| Swipe recents | Stays active               | No impact                | No impact        |
| Force Stop    | Stopped (no restart)       | Offline in 5 min         | No impact        |
| Uninstall     | Killed                     | Ghost offline in 5 min   | Orphaned account |

---

## Development

### Key Dependencies

| Library                   | Version       | Role                         |
|---------------------------|---------------|------------------------------|
| Paho MQTT v3              | 1.2.5         | MQTT client                  |
| Room                      | 2.6.1         | Offline buffer (SQLite)      |
| Hilt                      | 2.51          | Dependency injection         |
| WorkManager               | 2.9.0         | Periodic background tasks    |
| kotlinx-serialization     | 1.6.3         | JSON serialization           |
| OkHttp                    | 4.12.0        | APK download                 |
| security-crypto           | 1.1.0-alpha06 | EncryptedSharedPreferences   |
| Coroutines Android        | 1.8.0         | Async / suspend              |
| Material Components       | 1.12.0        | UI                           |

### Run Unit Tests

```bash
./gradlew test
```

### Useful ADB Logs

```bash
# All Orbis components
adb logcat -s OrbisService:I MqttManager:I CommandExecutor:D DeviceDataCollector:W OnboardingActivity:I BootReceiver:I

# Service only
adb logcat -s OrbisService:D

# MQTT only
adb logcat -s MqttManager:D

# Commands only
adb logcat -s CommandExecutor:D
```

### Backend Environment Variables Related to the Agent

```bash
# Registration secret (must match the "Register secret" field in the app)
MQTT_REGISTER_SECRET=orbis_register_secret

# Backend MQTT account (separate from device accounts)
MQTT_USERNAME=orbis-backend
MQTT_PASSWORD=<sha256_hash>
```

---

## Required Android Permissions

| Permission                     | Reason                                      |
|--------------------------------|---------------------------------------------|
| `INTERNET`                     | MQTT connection and APK download            |
| `ACCESS_NETWORK_STATE`         | Network type detection                      |
| `ACCESS_WIFI_STATE`            | Local IP for network scan                   |
| `FOREGROUND_SERVICE`           | Persistent service                          |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type (API 34)            |
| `RECEIVE_BOOT_COMPLETED`       | Auto-start on boot                          |
| `SCHEDULE_EXACT_ALARM`         | Precise AlarmManager restart                |
| `REQUEST_INSTALL_PACKAGES`     | APK installation (agent_update)             |
| `POST_NOTIFICATIONS`           | Persistent notification (Android 13+)       |
| `WAKE_LOCK`                    | Keep CPU active during background tasks     |
