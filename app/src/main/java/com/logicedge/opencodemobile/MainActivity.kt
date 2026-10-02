package com.logicedge.opencodemobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.logicedge.opencodemobile.data.ProfileStore
import com.logicedge.opencodemobile.data.SecretStore
import com.logicedge.opencodemobile.data.ServerRepository
import com.logicedge.opencodemobile.data.ThemeMode
import com.logicedge.opencodemobile.data.UiPrefs
import com.logicedge.opencodemobile.ui.OpenCodeNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = ServerRepository(ProfileStore(this), SecretStore(this))
        val uiPrefs = UiPrefs(this)
        setContent {
            val themeMode by uiPrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            OpenCodeNav(
                repository = repository,
                isDarkTheme = when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> systemDark
                },
            )
        }
    }
}
