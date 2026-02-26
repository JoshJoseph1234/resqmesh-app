package com.resqmesh.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object ConnectivityUtil {
    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    // THE FIX: Use a standard HTTP ping instead of a raw port 53 socket
    fun hasRealInternet(): Boolean {
        return try {
            // This is the exact URL Android uses internally to check for real internet!
            val url = URL("https://clients3.google.com/generate_204")
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Android")
            connection.setRequestProperty("Connection", "close")
            connection.connectTimeout = 2000 // Give it 2 seconds instead of 1.5
            connection.readTimeout = 2000
            connection.connect()

            // If it returns 204, the internet is 100% working
            val isWorking = connection.responseCode == 204

            if (!isWorking) {
                Log.w("ResQMesh_Network", "Internet check failed. HTTP Code: ${connection.responseCode}")
            }

            isWorking
        } catch (e: Exception) {
            // This will finally tell us in Logcat WHY it is failing!
            Log.e("ResQMesh_Network", "Internet check threw an error: ${e.message}")
            false
        }
    }
}