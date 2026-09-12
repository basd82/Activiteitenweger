package net.dikkenberg.activiteitenweger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.dikkenberg.activiteitenweger.platform.initAndroidPlatform
import net.dikkenberg.activiteitenweger.ui.ActiviteitenwegerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAndroidPlatform(applicationContext)
        setContent {
            ActiviteitenwegerApp()
        }
    }
}
