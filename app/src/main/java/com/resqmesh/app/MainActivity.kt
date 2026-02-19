package com.resqmesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.resqmesh.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    // Create the ViewModel automatically tied to the Activity lifecycle
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimpleResQMeshApp(viewModel = viewModel)
        }
    }
}