package io.github.miuzarte.scrcpyforandroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.miuzarte.scrcpyforandroid.connection.HandoffOutcome
import io.github.miuzarte.scrcpyforandroid.connection.ReceiverHandoff
import io.github.miuzarte.scrcpyforandroid.connection.parseHandoffTarget
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import kotlinx.coroutines.launch

/**
 * Entry point for the receiver handoff deep link `scrcaster://connect?h=..&p=..&t=..`.
 *
 * Transparent (no UI): resolves this phone's Wireless-debugging port and pushes it to the TV so
 * the viewer can connect even when the phone and TV sit on different subnets. Launching this
 * instead of the browser is what makes scanning with the system camera actually work.
 */
class HandoffActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppRuntime.init(applicationContext)
        val target = intent?.dataString?.let(::parseHandoffTarget)
        if (target == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            when (ReceiverHandoff.send(target)) {
                HandoffOutcome.SENT -> toast(R.string.device_handoff_sent)
                HandoffOutcome.NO_PORT -> {
                    toast(R.string.device_handoff_no_port)
                    openFallbackPage(target.payload)
                }
                HandoffOutcome.UNREACHABLE -> toast(R.string.device_handoff_failed)
            }
            finish()
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    /** No app-side port available: fall back to the TV's web form so the user can type it once. */
    private fun openFallbackPage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
