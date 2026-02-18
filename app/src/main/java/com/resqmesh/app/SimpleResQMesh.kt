package com.resqmesh.app


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
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

// --- THEME COLORS (AMOLED) ---
val PureBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF121212)
val EmergencyRed = Color(0xFFD32F2F)
val SignalCyan = Color(0xFF00BCD4)
val WarningAmber = Color(0xFFFFA000)
val TextWhite = Color(0xFFFFFFFF)

// --- MAIN SCREEN & NAVIGATION ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleResQMeshApp() {
    val navController = rememberNavController()
    // Simple State (Replacing Database for now)
    var sentMessages by remember { mutableStateOf(listOf<SosMessage>()) }
    var connectivity by remember { mutableStateOf(ConnectivityState.OFFLINE) }

    // Navigation Items
    val items = listOf(
        Screen("home", "SOS", Icons.Default.Home),
        Screen("status", "Status", Icons.Default.List),
        Screen("settings", "Settings", Icons.Default.Settings)
    )

    Scaffold(
        containerColor = PureBlack,
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EmergencyRed,
                            unselectedIconColor = Color.Gray,
                            indicatorColor = DarkSurface
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    connectivity = connectivity,
                    onSendSos = { type, msg ->
                        val newMsg = SosMessage(type = type, message = msg)
                        sentMessages = listOf(newMsg) + sentMessages // Add to top
                        connectivity = ConnectivityState.MESH_ACTIVE // Simulate connection
                    }
                )
            }
            composable("status") {
                StatusScreen(messages = sentMessages)
            }
            composable("settings") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Settings Page", color = TextWhite)
                }
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
    var selectedType by remember { mutableStateOf(SosType.MEDICAL) }
    var messageText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = if(connectivity == ConnectivityState.OFFLINE) Color.Gray else SignalCyan)
                Spacer(Modifier.width(8.dp))
                Text("Status: $connectivity", color = TextWhite)
            }
        }

        // Dropdown
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedType.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Type", color = Color.Gray) },
                trailingIcon = { IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ArrowDropDown, null, tint = TextWhite) } },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = EmergencyRed,
                    unfocusedBorderColor = Color.Gray
                )
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SosType.values().forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.name) },
                        onClick = { selectedType = type; expanded = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Message Input
        OutlinedTextField(
            value = messageText,
            onValueChange = { messageText = it },
            label = { Text("Message", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedBorderColor = EmergencyRed,
                unfocusedBorderColor = Color.Gray
            )
        )

        Spacer(Modifier.weight(1f))

        // SOS BUTTON
        Button(
            onClick = { onSendSos(selectedType, messageText) },
            colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
            shape = CircleShape,
            modifier = Modifier.size(200.dp)
        ) {
            Text("SOS", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        }
        Spacer(Modifier.weight(0.5f))
    }
}

@Composable
fun StatusScreen(messages: List<SosMessage>) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(messages) { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(msg.type.name, color = EmergencyRed, fontWeight = FontWeight.Bold)
                        Text(msg.status.name, color = WarningAmber, fontSize = 12.sp)
                    }
                    if(msg.message.isNotEmpty()) Text(msg.message, color = TextWhite, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp)),
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

data class Screen(val route: String, val title: String, val icon: ImageVector)