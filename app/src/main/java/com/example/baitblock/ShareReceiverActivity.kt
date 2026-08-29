package com.example.baitblock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleShare(intent)
    }

    private fun handleShare(intent: Intent) {

        if (
            intent.action != Intent.ACTION_SEND ||
            intent.type != "text/plain"
        ) {
            finish()
            return
        }

        val sharedText =
            intent.getStringExtra(Intent.EXTRA_TEXT)

        if (sharedText.isNullOrBlank()) {
            finish()
            return
        }

        val url = UrlExtractor.extract(sharedText)

        if (url == null) {
            finish()
            return
        }

        launchInspector(url)
    }

    private fun launchInspector(url: String) {

        val inspectorIntent =
            Intent(this, MainActivity::class.java).apply {
                putExtra(
                    IntentKeys.URL_TO_INSPECT,
                    url
                )
            }

        startActivity(inspectorIntent)
        finish()
    }
}