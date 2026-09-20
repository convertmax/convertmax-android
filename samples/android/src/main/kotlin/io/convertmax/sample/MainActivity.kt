package io.convertmax.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.convertmax.sdk.Configuration
import io.convertmax.sdk.Consent
import io.convertmax.sdk.Convertmax

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sdk = Convertmax.create(this, Configuration(writeKey = "public", appId = "demo-android"))
        setContent {
            var log by remember { mutableStateOf("Consent starts UNKNOWN. Grant it, then identify and track.") }
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Convertmax Android sample", style = MaterialTheme.typography.headlineSmall)
                    Text(log)
                    Button({ sdk.setConsent(Consent.GRANTED); log = "consent=GRANTED" }) { Text("Grant consent") }
                    Button({ sdk.identify("account-a"); log = "identify account-a" }) { Text("Identify") }
                    Button({ log = sdk.track("signup")?.let { "track signup userId=${it.userId}" } ?: "track dropped (consent?)" }) { Text("Track signup") }
                    Button({ log = sdk.screen("home")?.let { "screen home" } ?: "screen dropped (consent?)" }) { Text("Screen home") }
                    Button({ sdk.reset(); log = "reset identity" }) { Text("Reset") }
                }
            }
        }
    }
}
