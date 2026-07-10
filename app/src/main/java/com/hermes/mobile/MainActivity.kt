package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.mobile.ui.HermesApp
import com.hermes.mobile.ui.HermesTheme
import com.hermes.mobile.ui.HermesViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesTheme {
                val model: HermesViewModel = viewModel()
                HermesApp(model)
            }
        }
    }
}
