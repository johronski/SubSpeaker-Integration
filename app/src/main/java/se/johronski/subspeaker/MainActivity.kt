package se.johronski.subspeaker

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import se.johronski.subspeaker.ui.AppScreen
import se.johronski.subspeaker.ui.SubSpeakerTheme

class MainActivity : AppCompatActivity() {

    private val services: AppServices get() = (application as SubSpeakerApp).services

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ljusa systemfält med mörka ikoner, i samma papperston som appen.
        val paper = Color.rgb(0xF5, 0xF3, 0xEE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(paper, paper),
            navigationBarStyle = SystemBarStyle.light(paper, paper),
        )
        super.onCreate(savedInstanceState)
        // Skärmen hålls vaken så att mätningar inte avbryts.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SubSpeakerTheme {
                AppScreen(services)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        services.cast.onResume()
    }

    override fun onPause() {
        services.cast.onPause()
        super.onPause()
    }
}
