package com.example.baitblock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url =
            intent.getStringExtra(
                IntentKeys.URL_TO_INSPECT
            )

        setContent {
            BaitBlockScreen(url)
        }
    }
}

@Composable
fun BaitBlockScreen(url: String?) {

    Column(
        modifier = Modifier.padding(24.dp)
    ) {

        if (url != null) {

            Text("BaitBlock")

            Text("Inspecting:")

            Text(url)

        } else {

            Text(
                "Share a suspicious link with BaitBlock."
            )
        }
    }
}