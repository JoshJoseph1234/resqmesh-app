package com.resqmesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.text.SimpleDateFormat
import java.util.*
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.Color.Companion.DarkGray
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.content.BroadcastReceiver
import android.content.IntentFilter

// --- DATA MODELS ---
enum class SosType { MEDICAL, RESCUE, FOOD, TRAPPED, GENERAL, OTHER }
enum class DeliveryStatus { PENDING, RELAYED, DELIVERED }
enum class ConnectivityState { OFFLINE, MESH_ACTIVE, INTERNET }
enum class SendSosResult { SUCCESS, EMPTY_MESSAGE, HARDWARE_NOT_READY }

data class SosMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: SosType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: DeliveryStatus = DeliveryStatus.PENDING
)

data class QuickReply(val message: String, val type: SosType)

// --- THEME COLORS (Figma AMOLED) ---
val PureBlack = Color(0xFF000000)
val HeaderBlue = Color(0xFF141933)
val BrightCyan = Color(0xFF00D9FF)
val DarkCyanBg = Color(0xFF002B36)
val VibrantRed = Color(0xFFFF3B3B)
val InputGrayBg = Color(0xFF16161A)
val BorderGray = Color(0xFF2C2C35)
val TextWhite = Color(0xFFFFFFFF)
val TextLightGray = Color(0xFFA0A0AB)
val AmberText = Color(0xFFFFB300)
val AmberBg = Color(0xFF332200)

// New Colors for Settings & Status
val SuccessGreen = Color(0xFF00E676)
val SuccessGreenBg = Color(0xFF003314)
val RelayedBlue = Color(0xFF00B0FF)
val RelayedBlueBg = Color(0xFF002233)
val IconBlueBg = Color(0xFF1E3A8A)
val IconPurpleBg = Color(0xFF4A148C)
val IconGreenBg = Color(0xFF1B5E20)
val IconGrayBg = Color(0xFF424242)
val InfoCardBlueBg = Color(0xFF1A237E)
val InfoCardRedBg = Color(0xFF3E1010)

@Composable
fun HardwareStateMonitor(viewModel: com.resqmesh.app.viewmodel.MainViewModel) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.syncHardwareState()
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        }

        context.registerReceiver(receiver, filter)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleResQMeshApp(viewModel: com.resqmesh.app.viewmodel.MainViewModel) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        viewModel.syncHardwareState()
    }

    HardwareStateMonitor(viewModel)

    val sentMessages by viewModel.sentMessages.collectAsState()
    val connectivity by viewModel.connectivity.collectAsState()

    val items = listOf(
        Screen("home", "Home", Icons.Default.Home),
        Screen("alerts", "Alerts", Icons.Default.Warning),
        Screen("status", "Status", Icons.Default.MailOutline),
        Screen("settings", "Settings", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = PureBlack,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 1.dp, color = BorderGray)
                NavigationBar(containerColor = PureBlack) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title, fontSize = 10.sp) },
                            selected = currentRoute == screen.route,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BrightCyan,
                                selectedTextColor = BrightCyan,
                                unselectedIconColor = TextLightGray,
                                unselectedTextColor = TextLightGray,
                                indicatorColor = PureBlack
                            ),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding).background(PureBlack)
        ) {
            composable("home") {
                HomeScreen(
                    connectivity = connectivity,
                    onSendSos = { type, msg -> viewModel.sendSos(type, msg) }
                )
            }
            composable("alerts") {
                com.resqmesh.app.ui.theme.AlertsScreen(viewModel = viewModel)
            }
            composable("status") {
                StatusScreen(messages = sentMessages.map {
                    SosMessage(it.id, it.type, it.message, it.timestamp, it.status)
                })
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectivity: ConnectivityState,
    onSendSos: (SosType, String) -> SendSosResult
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf(SosType.GENERAL) }
    var messageText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // NEW: We need to access the hardware managers to see exactly what is turned off
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

    val quickReplies = listOf(
        QuickReply("Medical Help", SosType.MEDICAL),
        QuickReply("Trapped", SosType.TRAPPED),
        QuickReply("Need Evac", SosType.RESCUE),
        QuickReply("Need Food/H2O", SosType.FOOD),
        QuickReply("Rescue Me", SosType.RESCUE)
    )

    // NEW: The Bluetooth Pop-up launcher for the Home Screen!
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Bluetooth Activated. Please press SEND SOS again!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Bluetooth activation denied. Cannot send SOS.", Toast.LENGTH_SHORT).show()
        }
    }

    val sendAction = {
        val result = onSendSos(selectedType, messageText)
        when (result) {
            SendSosResult.SUCCESS -> {
                Toast.makeText(context, "SOS Message Broadcasting!", Toast.LENGTH_SHORT).show()
                selectedType = SosType.GENERAL
                messageText = ""
                expanded = false
                focusManager.clearFocus()
            }
            SendSosResult.EMPTY_MESSAGE -> {
                Toast.makeText(context, "FAILED: Message cannot be empty.", Toast.LENGTH_LONG).show()
            }
            SendSosResult.HARDWARE_NOT_READY -> {
                // THE FIX: Actively prompt the user to turn on the missing hardware!
                val isGpsOn = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                val isBtOn = bluetoothAdapter?.isEnabled == true

                if (!isGpsOn) {
                    Toast.makeText(context, "Please enable GPS Location to broadcast SOS", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }

                if (!isBtOn) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                }
            }
        }
    }

    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            sendAction()
        } else {
            Toast.makeText(context, "Permissions required for Mesh Networking", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                })
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(HeaderBlue).padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text("ResQMesh", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Emergency Response Network", color = Color(0xFF8C9EFF), fontSize = 14.sp)
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(DarkCyanBg).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (statusText, statusIcon) = when(connectivity) {
                    ConnectivityState.INTERNET -> "Cloud & Mesh Active" to Icons.Default.CloudQueue
                    ConnectivityState.MESH_ACTIVE -> "Mesh Network Active" to Icons.Default.Wifi
                    else -> "Offline" to Icons.Default.CloudOff
                }
                Icon(statusIcon, null, tint = BrightCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = BrightCyan, fontSize = 14.sp)
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Emergency Type", color = TextWhite, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedType.name.replace("_", " "),
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = InputGrayBg, focusedContainerColor = InputGrayBg,
                            unfocusedBorderColor = BorderGray, focusedBorderColor = BrightCyan,
                            unfocusedTextColor = TextWhite, focusedTextColor = TextWhite
                        )
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(InputGrayBg)) {
                        SosType.values().forEach { type ->
                            DropdownMenuItem(text = { Text(type.name, color = TextWhite) }, onClick = { selectedType = type; expanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "QUICK REPLIES",
                    color = TextLightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    items(quickReplies) { reply ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (messageText == reply.message) BrightCyan else Color(0xFF1E1E1E))
                                .clickable {
                                    messageText = reply.message
                                    selectedType = reply.type
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = reply.message,
                                color = if (messageText == reply.message) PureBlack else TextWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { newText ->
                        if (newText.length <= 14) { // Safety constraint based on our payload math!
                            messageText = newText.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,'?!-()/" }
                        }
                    },
                    label = { Text("Custom Message (Optional)", color = TextLightGray) },
                    placeholder = { Text("Max 14 chars...", color = DarkGray) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrightCyan,
                        unfocusedBorderColor = BorderGray,
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    supportingText = {
                        Text(
                            text = "${messageText.length} / 14",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            color = if (messageText.length >= 14) VibrantRed else TextLightGray
                        )
                    }
                )
            }

            Column {
                Button(
                    onClick = {
                        val allPermissionsGranted = permissionsToRequest.all {
                            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (allPermissionsGranted) {
                            sendAction()
                        } else {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantRed),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = TextWhite)
                    Spacer(Modifier.width(12.dp))
                    Text("SEND SOS", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Send, null, tint = TextWhite)
                }
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AmberBg).border(1.dp, AmberText.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(16.dp)) {
                    Text("Note: Your SOS will be broadcast to nearby devices and emergency responders.", color = AmberText, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
fun StatusScreen(messages: List<SosMessage>) {
    Column(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            Text("Message Status", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Track your SOS messages", color = TextLightGray, fontSize = 14.sp)
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            items(messages, key = { it.id }) { msg ->
                val (statusColor, statusBg, statusIcon) = when (msg.status) {
                    DeliveryStatus.DELIVERED -> Triple(SuccessGreen, SuccessGreenBg, Icons.Default.CheckCircle)
                    DeliveryStatus.RELAYED -> Triple(RelayedBlue, RelayedBlueBg, Icons.Default.Wifi)
                    DeliveryStatus.PENDING -> Triple(AmberText, AmberBg, Icons.Default.Refresh)
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = InputGrayBg),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(msg.type.name.replace("_", " "), color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)

                            Row(
                                modifier = Modifier.border(1.dp, statusColor, RoundedCornerShape(50)).background(statusBg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(msg.status.name, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Text(SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.timestamp)), color = TextLightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                        if(msg.message.isNotEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().background(PureBlack, RoundedCornerShape(8.dp)).padding(12.dp)) {
                                Text("\"${msg.message}\"", color = TextWhite, fontSize = 14.sp)
                            }
                        }

                        if (msg.status != DeliveryStatus.PENDING) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                                Icon(Icons.Default.Wifi, null, tint = TextLightGray, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Relayed through mesh", color = TextLightGray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: com.resqmesh.app.viewmodel.MainViewModel) {
    val context = LocalContext.current

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val bluetoothEnabled by viewModel.bluetoothEnabled.collectAsState()
    val wifiDirectEnabled by viewModel.wifiDirectEnabled.collectAsState()

    var pendingAction by remember { mutableStateOf("") }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.setBluetoothEnabled(true)
            Toast.makeText(context, "Bluetooth Activated", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.setBluetoothEnabled(false)
            Toast.makeText(context, "Bluetooth activation denied", Toast.LENGTH_SHORT).show()
        }
    }

    val permissionsToRequest = remember {
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }

        if (allGranted) {
            if (pendingAction == "BLUETOOTH") {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    Toast.makeText(context, "Please enable GPS Location for Mesh", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }

                if (bluetoothAdapter?.isEnabled == false) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                } else {
                    viewModel.setBluetoothEnabled(true)
                    viewModel.kickstartMeshEars()
                }
            } else if (pendingAction == "WIFI") {
                try {
                    if (!wifiManager.isWifiEnabled) {
                        Toast.makeText(context, "Please turn on Wi-Fi for Mesh Networking", Toast.LENGTH_LONG).show()
                        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
                        context.startActivity(wifiIntent)
                    }
                    viewModel.setWifiDirectEnabled(true)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not access Wi-Fi hardware", Toast.LENGTH_SHORT).show()
                    viewModel.setWifiDirectEnabled(false)
                }
            }
        } else {
            if (pendingAction == "BLUETOOTH") viewModel.setBluetoothEnabled(false)
            if (pendingAction == "WIFI") viewModel.setWifiDirectEnabled(false)
            Toast.makeText(context, "Permissions required for Mesh Networking", Toast.LENGTH_LONG).show()
        }
        pendingAction = ""
    }

    Column(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            Text("Settings", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Configure ResQMesh", color = TextLightGray, fontSize = 14.sp)
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp)) {
            item {
                Text("CONNECTIVITY", color = TextLightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                SettingsRowToggle(
                    icon = Icons.Default.Bluetooth, iconBg = IconBlueBg, title = "Bluetooth",
                    subtitle = if(bluetoothEnabled) "Enabled" else "Disabled",
                    checked = bluetoothEnabled,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            pendingAction = "BLUETOOTH"
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                SettingsRowToggle(
                    icon = Icons.Default.Wifi, iconBg = IconPurpleBg, title = "Wi-Fi Direct",
                    subtitle = if(wifiDirectEnabled) "Enabled" else "Disabled",
                    checked = wifiDirectEnabled,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            pendingAction = "WIFI"
                            permissionLauncher.launch(permissionsToRequest)
                        } else {
                            viewModel.setWifiDirectEnabled(false)
                        }
                    }
                )

                Spacer(Modifier.height(32.dp))
                Text("GENERAL", color = TextLightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                SettingsRowArrow(icon = Icons.Default.Language, iconBg = IconGreenBg, title = "Language", subtitle = "English")
                Spacer(Modifier.height(16.dp))
                SettingsRowArrow(icon = Icons.Default.Info, iconBg = IconGrayBg, title = "About", subtitle = "Version 1.0.0")

                Spacer(Modifier.height(32.dp))

                Box(modifier = Modifier.fillMaxWidth().background(InfoCardBlueBg, RoundedCornerShape(12.dp)).padding(16.dp)) {
                    Column {
                        Text("About ResQMesh", color = BrightCyan, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Text("ResQMesh enables peer-to-peer emergency communication without internet connectivity. Your device creates a mesh network with nearby devices to relay SOS messages even during disasters.", color = Color(0xFF8C9EFF), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth().background(InfoCardRedBg, RoundedCornerShape(12.dp)).padding(16.dp)) {
                    Column {
                        Text("Important Notice", color = VibrantRed, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Text("ResQMesh is designed to complement, not replace, official emergency services. Always contact local emergency services when possible.", color = Color(0xFFFF8A80), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsRowToggle(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(iconBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = TextWhite, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextLightGray, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = BrightCyan,
                uncheckedThumbColor = TextLightGray,
                uncheckedTrackColor = BorderGray
            )
        )
    }
}

@Composable
fun SettingsRowArrow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(iconBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = TextWhite, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = TextLightGray, fontSize = 12.sp)
            }
        }
        Icon(Icons.Default.KeyboardArrowRight, null, tint = TextLightGray)
    }
}

data class Screen(val route: String, val title: String, val icon: ImageVector)
