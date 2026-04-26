package com.znet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.znet.app.ui.ZnetAppScreen
import com.znet.app.ui.theme.ZnetTheme
import com.znet.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as ZnetApp
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModel.Factory(application, app.container)
            )
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            ZnetTheme {
                ZnetAppScreen(state = state, viewModel = viewModel)
            }
        }
    }
}
