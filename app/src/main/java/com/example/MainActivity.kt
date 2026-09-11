package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.accounting.presentation.MainAppScreen
import com.example.accounting.presentation.features.splash.SplashScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        var showSplash by remember { mutableStateOf(true) }
        Crossfade(targetState = showSplash, animationSpec = tween(350), label = "splashToApp") { splashing ->
          if (splashing) {
            SplashScreen(onFinished = { showSplash = false })
          } else {
            MainAppScreen()
          }
        }
      }
    }
  }
}


