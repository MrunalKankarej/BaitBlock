package com.example.baitblock.vpn

import android.net.VpnService
import android.content.Intent

class BaitBlockVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Real VPN setup (establish(), packet loop) comes later.
        return START_STICKY
    }
}
