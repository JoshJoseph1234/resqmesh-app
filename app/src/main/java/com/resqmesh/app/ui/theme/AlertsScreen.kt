package com.resqmesh.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.data.SosMessageEntity
import com.resqmesh.app.viewmodel.MainViewModel
import java.util.concurrent.TimeUnit

// --- FIGMA COLOR PALETTE ---
val PureBlack = Color(0xFF000000)
val BrightCyan = Color(0xFF00D9FF)
val VibrantRed = Color(0xFFFF3B3B)
val Amber400 = Color(0xFFFFCA28)
val Green400 = Color(0xFF66BB6A)
val DarkGray800 = Color(0xFF1A1A1A)
val BorderGray = Color(0xFF333333)
val TextLightGray = Color(0xFFAAAAAA)
val DeepBlueTint = Color(0xFF0A1E2B)
val BorderBlueTint = Color(0xFF163B50)

// --- MATH & UTILS ---
// Calculates meters between two GPS points
fun getDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val r = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return (r * c).toInt()
}

// Converts Unix timestamp into "3m ago" format
fun getTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return if (minutes < 1) "Just now" else "${minutes}m ago"
}

// Generates "User #A3F9" from the MAC Address / DB ID
fun getShortId(fullId: String): String {
    return if (fullId.length >= 4) fullId.takeLast(4).uppercase() else "UNKN"
}

@Composable
fun AlertsScreen(viewModel: MainViewModel) {
    // Read all messages directly from the local Room Database
    val allMessages by viewModel.sentMessages.collectAsState()

    // Filter out our own messages. We only want to see incoming alerts!
    val incomingAlerts = allMessages.filter { it.status == DeliveryStatus.DELIVERED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack) // True AMOLED Black
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // --- HEADER ---
        Text(
            text = "Nearby Alerts",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "SOS signals in your area",
            color = TextLightGray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 24.dp, top = 4.dp)
        )

        // --- SUMMARY CARD (The Blue Box) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DeepBlueTint)
                .border(1.dp, BorderBlueTint, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = BrightCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "${incomingAlerts.size} Alerts Detected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Within 2km radius",
                        color = BrightCyan,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- ALERT CARDS LIST ---
        if (incomingAlerts.isEmpty()) {
            Text(
                text = "Scanning for nearby emergency signals...",
                color = TextLightGray,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp) // Leave space for bottom nav bar!
            ) {
                items(incomingAlerts) { alert ->
                    AlertCard(alert)
                }
            }
        }
    }
}

@Composable
fun AlertCard(alert: SosMessageEntity) {
    // Fallback coordinates (Kochi) for math representation
    val myLat = 9.9312
    val myLon = 76.2673

    val meters = if (alert.latitude != null && alert.longitude != null) {
        getDistanceInMeters(myLat, myLon, alert.latitude, alert.longitude)
    } else {
        0
    }

    // Determine the color of the outline tag based on string match of enum name
    val (tagColor, typeString) = when {
        alert.type.name.contains("MEDICAL") -> Pair(VibrantRed, "Medical Emergency")
        alert.type.name.contains("RESCUE") -> Pair(Amber400, "Need Rescue")
        alert.type.name.contains("FOOD") -> Pair(Green400, "Need Food/Water")
        else -> Pair(VibrantRed, alert.type.name.replace("_", " "))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkGray800)
            .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column {
            // --- TOP ROW: Pill Tag & Distance ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Outlined Pill Tag
                Box(
                    modifier = Modifier
                        .border(1.dp, tagColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = typeString,
                        color = tagColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Distance Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = null,
                        tint = VibrantRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${meters}m",
                        color = VibrantRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- MIDDLE ROW: User ID and Time ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = TextLightGray, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("User #${getShortId(alert.id)}", color = TextLightGray, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = TextLightGray, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(getTimeAgo(alert.timestamp), color = TextLightGray, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- INNER MESSAGE BOX ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(PureBlack)
                    .padding(12.dp)
            ) {
                Text(
                    text = alert.message,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = BorderGray, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // --- VIEW DETAILS BUTTON ---
            Text(
                text = "View Details",
                color = BrightCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { /* TODO: Open Map */ }
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}