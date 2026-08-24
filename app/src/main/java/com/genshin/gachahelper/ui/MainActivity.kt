package com.genshin.gachahelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.genshin.gachahelper.ui.theme.GenshinGachaHelperTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GenshinGachaHelperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GachaAppNavHost()
                }
            }
        }
    }
}
