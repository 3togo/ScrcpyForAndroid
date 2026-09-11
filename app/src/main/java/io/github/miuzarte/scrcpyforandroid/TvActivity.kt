package io.github.miuzarte.scrcpyforandroid

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.miuzarte.scrcpyforandroid.connection.ConnectionEvent
import io.github.miuzarte.scrcpyforandroid.connection.ConnectionScreen
import io.github.miuzarte.scrcpyforandroid.connection.ConnectionViewModel
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import kotlinx.coroutines.launch
import java.util.Locale

/** TV launcher adapter. Connection behavior and Compose content live outside the Activity. */
class TvActivity : FragmentActivity() {
    private lateinit var model: ConnectionViewModel

    override fun attachBaseContext(newBase: Context) {
        val language = MainActivity.getAppLanguageTag(newBase)
        super.attachBaseContext(if (language.isEmpty()) newBase else newBase.createConfigurationContext(
            Configuration(newBase.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        AppRuntime.init(applicationContext)
        if (AppRuntime.scrcpy == null) AppRuntime.scrcpy = Scrcpy(applicationContext)
        AppScreenOn.register(window)
        model = ViewModelProvider(this)[ConnectionViewModel::class.java]
        setContent { ConnectionScreen(model.controller, remote = true) }
        onBackPressedDispatcher.addCallback(this) { model.controller.back() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.controller.events.collect { event ->
                    when (event) {
                        ConnectionEvent.PLAYBACK -> startActivity(StreamActivity.createIntent(this@TvActivity, tvReceiver = true))
                        ConnectionEvent.FINISH -> finish()
                    }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.CINNAMON_BUN &&
            checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
        } else model.onLaunch(autoReconnect = savedInstanceState == null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) model.onLaunch(autoReconnect = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.controller.refresh()
    }

    override fun onStop() {
        if (::model.isInitialized && !isChangingConfigurations) model.controller.stopPairing()
        super.onStop()
    }

    override fun onDestroy() {
        AppScreenOn.unregister(window)
        super.onDestroy()
    }
}
