package se.johronski.subspeaker

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import se.johronski.subspeaker.ui.DiagnosticsScreen
import se.johronski.subspeaker.ui.SubSpeakerTheme

class MainActivity : AppCompatActivity() {

    private val services: AppServices get() = (application as SubSpeakerApp).services

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Skärmen hålls vaken så att mätningar inte avbryts.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            SubSpeakerTheme {
                DiagnosticsScreen(services)
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
