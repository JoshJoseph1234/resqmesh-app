# ResQMesh 🛜 - Offline Emergency Communication Network

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=android&logoColor=white)
![Room Database](https://img.shields.io/badge/Room_Database-0081CB?style=for-the-badge&logo=sqlite&logoColor=white)

**ResQMesh** is an Android application that transforms ordinary smartphones into a decentralized, self-healing Bluetooth Low Energy (BLE) mesh network. It enables peer-to-peer SOS broadcasting and message relaying during natural disasters or cellular blackouts when traditional internet and cell towers fail.

Built as a B.Tech Computer Science & Engineering final year project.

---

## 🚨 The Problem
During severe natural disasters (earthquakes, floods, hurricanes), cellular infrastructure is often the first thing to collapse. People trapped under debris or isolated by floodwaters have smartphones with remaining battery life, but no way to broadcast their GPS location or medical needs to nearby rescue teams.

## 💡 The Solution
ResQMesh completely bypasses the need for ISPs, cell towers, or Wi-Fi routers. It utilizes the physical **Bluetooth Low Energy (BLE)** antennas inside smartphones to create an ad-hoc "Bucket Brigade" network. When one person sends an SOS, the signal hops from phone to phone, mathematically expanding the rescue radius until it reaches a first responder.

---

## ✨ Key Features
* 📡 **100% Offline Connectivity:** Uses connectionless BLE Advertising and Scanning to broadcast payloads without requiring devices to explicitly "pair."
* 🔗 **Store-and-Forward Routing:** Devices seamlessly act as routers. When an SOS is received, the app saves it locally and instantly re-broadcasts it to the next neighborhood.
* 🛡️ **Broadcast Storm Protection:** Implements mathematical payload hashing (Echo Cancellation) to prevent infinite loops and network spam.
* 📍 **Live GPS & Haversine Math:** Embeds real-time coordinates directly into the BLE byte payload and calculates the exact distance (in meters) to the victim.
* 🗺️ **Google Maps Integration:** One-click intent routing that instantly opens turn-by-turn directions to the trapped user.
* 🔋 **Hardware-Aware Engine:** Dynamically monitors physical Bluetooth/GPS chips and safely suspends operations if antennas are disabled, preventing battery drain.
* 📱 **AMOLED Dark Mode UI:** A battery-saving, high-contrast interface built entirely in Jetpack Compose.

---

## 🏗️ System Architecture 



1. **The Packer:** The user's SOS category (e.g., Medical, Food) and GPS coordinates are compressed into a strict 31-byte UTF-8 payload.
2. **The Broadcaster:** The BLE Advertiser shouts the payload using `ADVERTISE_MODE_LOW_LATENCY`.
3. **The Scanner:** Nearby devices continuously scan for the specific `87bd42f3...` ResQMesh UUID.
4. **The Cache:** Incoming payloads are hashed. If the hash exists in the memory cache, it is dropped (preventing echoes). If it is new, it is unpacked.
5. **The Database:** Unpacked coordinates and messages are saved persistently to the local SQLite (Room) Database.
6. **The Relay:** The newly received payload is fed back into the Broadcaster to continue the chain.

---

## 🛠️ Tech Stack
* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material Design 3)
* **Local Storage:** Room Database (SQLite) & Kotlin StateFlows
* **Hardware APIs:** * `android.bluetooth.le.*` (BLE Scanner & Advertiser)
  * `FusedLocationProviderClient` (High-accuracy GPS)
* **Architecture:** MVVM (Model-View-ViewModel)
* **Concurrency:** Kotlin Coroutines & `viewModelScope`

---

## 🚀 Installation & Setup

1. **Clone the repository:**
   ```bash
   git clone [https://github.com/JoshJoseph1234/resqmesh-app.git](https://github.com/JoshJoseph1234/resqmesh-app.git)

2.Open in Android Studio:
Ensure you are using Android Studio Iguana or newer.

3.Build the Project:
Sync Gradle files to download the required Jetpack Compose and Room dependencies.

4.Run on Physical Devices:
⚠️ Note: Bluetooth Low Energy features cannot be tested on an Android Emulator. You must deploy the app to at least two physical Android devices to test the mesh routing.

Required Permissions
Upon first launch, the app will request:

ACCESS_FINE_LOCATION (Required for BLE scanning on modern Android)

BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN, BLUETOOTH_CONNECT (Android 12+)
