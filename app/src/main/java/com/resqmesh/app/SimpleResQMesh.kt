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

// --- DATA MODELS ---
enum class SosType { MEDICAL, RESCUE, FOOD, TRAPPED, GENERAL }
enum class DeliveryStatus { PENDING, RELAYED, DELIVERED }
enum class ConnectivityState { OFFLINE, MESH_ACTIVE, INTERNET }

data class SosMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: SosType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: DeliveryStatus = DeliveryStatus.PENDING
)

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

// --- MAIN SCREEN & NAVIGATION ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleResQMeshApp(viewModel: com.resqmesh.app.viewmodel.MainViewModel) {
    val navController = rememberNavController()

    val sentMessages by viewModel.sentMessages.collectAsState()
    val connectivity by viewModel.connectivity.collectAsState()

    // 3-Tab Layout (Alerts removed)
    val items = listOf(
        Screen("home", "SOS", Icons.Default.Warning),
        Screen("status", "Messages", Icons.Default.MailOutline),
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
                                selectedIconColor = VibrantRed,
                                selectedTextColor = VibrantRed,
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
            composable("status") {
                StatusScreen(messages = sentMessages.map {
                    SosMessage(it.id, it.type, it.message, it.timestamp, it.status)
                })
            }
            composable("settings") {
                SettingsScreen()
            }
        }
    }
}

// --- SCREENS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    connectivity: ConnectivityState,
    onSendSos: (SosType, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedType by remember { mutableStateOf(SosType.GENERAL) }
    var messageText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        // TOP HEADER
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
                Icon(Icons.Default.Wifi, null, tint = BrightCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if(connectivity == ConnectivityState.OFFLINE) "Offline" else "Mesh Network Active", color = BrightCyan, fontSize = 14.sp)
            }
        }

        // FORM SECTION
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
                Text("Additional Message (Optional)", color = TextWhite, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { if (it.length <= 200) messageText = it },
                    placeholder = { Text("Add any additional details...", color = TextLightGray) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = InputGrayBg, focusedContainerColor = InputGrayBg,
                        unfocusedBorderColor = BorderGray, focusedBorderColor = BrightCyan,
                        unfocusedTextColor = TextWhite, focusedTextColor = TextWhite
                    )
                )
                Text("${messageText.length}/200 characters", color = TextLightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            // BOTTOM SECTION
            Column {
                Button(
                    onClick = {
                        onSendSos(selectedType, messageText)
                        android.widget.Toast.makeText(context, "SOS Message Sent!", android.widget.Toast.LENGTH_SHORT).show()
                        selectedType = SosType.GENERAL
                        messageText = ""
                        expanded = false
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
        // HEADER
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            Text("Message Status", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Track your SOS messages", color = TextLightGray, fontSize = 14.sp)
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            items(messages) { msg ->
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

                            // Status Pill
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
fun SettingsScreen() {
    var bluetoothEnabled by remember { mutableStateOf(true) }
    var wifiDirectEnabled by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        // HEADER
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)) {
            Text("Settings", color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Configure ResQMesh", color = TextLightGray, fontSize = 14.sp)
        }

        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp)) {
            item {
                Text("CONNECTIVITY", color = TextLightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                SettingsRowToggle(icon = Icons.Default.Bluetooth, iconBg = IconBlueBg, title = "Bluetooth", subtitle = "Enabled", checked = bluetoothEnabled, onCheckedChange = { bluetoothEnabled = it })
                Spacer(Modifier.height(16.dp))
                SettingsRowToggle(icon = Icons.Default.Wifi, iconBg = IconPurpleBg, title = "Wi-Fi Direct", subtitle = "Enabled", checked = wifiDirectEnabled, onCheckedChange = { wifiDirectEnabled = it })

                Spacer(Modifier.height(32.dp))
                Text("GENERAL", color = TextLightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                SettingsRowArrow(icon = Icons.Default.Language, iconBg = IconGreenBg, title = "Language", subtitle = "English")
                Spacer(Modifier.height(16.dp))
                SettingsRowArrow(icon = Icons.Default.Info, iconBg = IconGrayBg, title = "About", subtitle = "Version 1.0.0")

                Spacer(Modifier.height(32.dp))

                // Info Cards
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

// Helper components for Settings
@Composable
fun SettingsRowToggle(icon: ImageVector, iconBg: Color, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = TextWhite, checkedTrackColor = BrightCyan))
    }
}

@Composable
fun SettingsRowArrow(icon: ImageVector, iconBg: Color, title: String, subtitle: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
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