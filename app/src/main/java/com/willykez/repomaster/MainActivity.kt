package com.willykez.repomaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.willykez.repomaster.data.AppearancePrefs
import com.willykez.repomaster.navigation.RepoMasterApp
import com.willykez.repomaster.ui.theme.RepoMasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by AppearancePrefs.themeMode.collectAsState()
            val dynamicColor by AppearancePrefs.dynamicColor.collectAsState()
            val darkTheme = when (themeMode) {
                AppearancePrefs.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppearancePrefs.ThemeMode.LIGHT -> false
                AppearancePrefs.ThemeMode.DARK -> true
            }
            RepoMasterTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RepoMasterApp()
                }
            }
        }
    }
}
