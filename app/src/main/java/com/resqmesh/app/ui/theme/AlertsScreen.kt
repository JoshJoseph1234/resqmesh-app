package com.resqmesh.app.ui.theme

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

fun getDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return (r * c).toInt()
}

fun getTimeAgo(timestamp: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - timestamp)
    return if (minutes < 1) "Just now" else "${minutes}m ago"
}

fun getShortId(fullId: String): String {
    val senderPart = fullId.substringBefore("_")
    return if (senderPart.length >= 4) senderPart.takeLast(4).uppercase() else "UNKN"
}

@Composable
fun AlertsScreen(viewModel: MainViewModel) {
    val allMessages by viewModel.sentMessages.collectAsState()
    val incomingAlerts = allMessages.filter { it.status == DeliveryStatus.RELAYED }

    val myLat by viewModel.myCurrentLat.collectAsState()
    val myLon by viewModel.myCurrentLon.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(PureBlack).padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("Nearby Alerts", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("SOS signals in your area", color = TextLightGray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp, top = 4.dp))

        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DeepBlueTint).border(1.dp, BorderBlueTint, RoundedCornerShape(8.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = BrightCyan, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("${incomingAlerts.size} Alerts Detected", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Within Mesh Range", color = BrightCyan, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (incomingAlerts.isEmpty()) {
            Text("Scanning for nearby emergency signals...", color = TextLightGray, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally), textAlign = TextAlign.Center)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(incomingAlerts) { alert ->
                    AlertCard(alert, myLat, myLon)
                }
            }
        }
    }
}

@Composable
fun AlertCard(alert: SosMessageEntity, myLat: Double?, myLon: Double?) {
    val context = LocalContext.current

    val meters = if (alert.latitude != null && alert.longitude != null && myLat != null && myLon != null) {
        getDistanceInMeters(myLat, myLon, alert.latitude, alert.longitude)
    } else {
        0
    }

    val (tagColor, typeString) = when {
        alert.type.name.contains("MEDICAL") -> Pair(VibrantRed, "Medical Emergency")
        alert.type.name.contains("RESCUE") -> Pair(Amber400, "Need Rescue")
        alert.type.name.contains("FOOD") -> Pair(Green400, "Need Food/H2O")
        else -> Pair(VibrantRed, alert.type.name.replace("_", " "))
    }

    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkGray800).border(1.dp, BorderGray, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.border(1.dp, tagColor, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(typeString, color = tagColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NearMe, null, tint = VibrantRed, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${meters}m", color = VibrantRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = TextLightGray, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("User #${getShortId(alert.id)}", color = TextLightGray, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, null, tint = TextLightGray, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(getTimeAgo(alert.timestamp), color = TextLightGray, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PureBlack).padding(12.dp)) {
                Text(alert.message, color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = BorderGray, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "View Directions",
                color = BrightCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (alert.latitude != null && alert.longitude != null) {
                            val uri = Uri.parse("google.navigation:q=${alert.latitude},${alert.longitude}")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                            mapIntent.setPackage("com.google.android.apps.maps")
                            if (mapIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(mapIntent)
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=${alert.latitude},${alert.longitude}")))
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}