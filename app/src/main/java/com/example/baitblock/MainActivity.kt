package com.example.baitblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.baitblock.vpn.BaitBlockVpnService

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            prepareVpn()
        }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startVpn()
            else onVpnDeclined()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(IntentKeys.URL_TO_INSPECT)

        setContent {
            BaitBlockScreen(
                url = url,
                onEnableClick = { requestNotificationPermission() }
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareVpn()
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent)
        else startVpn()
    }

    private fun startVpn() {
        val intent = Intent(this, BaitBlockVpnService::class.java)
        startForegroundService(intent)
        // TODO: update UI to protected state
    }

    private fun onVpnDeclined() {
        // TODO: show "protection disabled, tap to retry" state
    }
}

@Composable
fun BaitBlockScreen(
    url: String?,
    onEnableClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("BaitBlock")
        Spacer(Modifier.height(16.dp))

        if (url != null) {
            Text("Inspecting:")
            Text(url)
        } else {
            Text("Share a suspicious link with BaitBlock.")
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onEnableClick) {
            Text("Enable protection")
        }
    }
}