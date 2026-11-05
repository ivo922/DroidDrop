package dev.ivo.droiddrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ivo.droiddrop.core.pairing.Pairing

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phoneCode = Pairing.displayCode(AndroidIdentity.loadOrCreate().fingerprint)
        setContent {
            MaterialTheme {
                Text("Phone code: $phoneCode", Modifier.padding(24.dp))
            }
        }
    }
}
