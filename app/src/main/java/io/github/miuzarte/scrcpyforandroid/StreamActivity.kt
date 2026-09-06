package io.github.miuzarte.scrcpyforandroid

import android.R.drawable
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import android.view.KeyEvent
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import kotlinx.coroutines.channels.Channel
import androidx.core.app.PictureInPictureParamsCompat.Builder
import androidx.core.content.ContextCompat
import androidx.core.pip.BasicPictureInPicture
import androidx.fragment.app.FragmentActivity
import io.github.miuzarte.scrcpyforandroid.pages.StreamScreen
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import io.github.miuzarte.scrcpyforandroid.services.PictureInPictureActionReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

class StreamActivity: FragmentActivity() {
    private val basicPip by lazy { BasicPictureInPicture(this, ContextCompat.getMainExecutor(this)) }

    private val pipActionReceiver = PictureInPictureActionReceiver()
    private var isPipActionReceiverRegistered = false

    // 是否处于 pip
    // 回到全屏时会因重建而变回初始值
    private val _pipModeState = MutableStateFlow(false)
    val pipModeState: StateFlow<Boolean> = _pipModeState

    val pipStopAction: RemoteAction by lazy {
        RemoteAction(
            Icon.createWithResource(this, drawable.ic_menu_close_clear_cancel),
            getString(R.string.password_stop_mirroring),
            getString(R.string.password_stop_mirroring),
            PictureInPictureActionReceiver.createPendingIntent(this),
        )
    }

    // 每次 进出全屏/进出画中画
    // 都会重建 activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usePhoneAspect.value = getSharedPreferences("tv_connection", MODE_PRIVATE)
            .getBoolean("phone_aspect", true)
        currentActivityRef = WeakReference(this)
        if (tvReceiverMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val scrcpy = AppRuntime.scrcpy
            if (scrcpy != null) {
                tvRemote = TvRemoteController(ScrcpyTvRemoteInput(scrcpy))
                lifecycleScope.launch {
                    try {
                        for (command in tvCommands) {
                            try {
                                command(tvRemote!!)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                runCatching { tvRemote?.release() }
                                android.util.Log.w("TvRemote", "Input failed", error)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastInputError > 3000) {
                                    lastInputError = now
                                    android.widget.Toast.makeText(this@StreamActivity,
                                        R.string.tv_input_failed, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) { runCatching { tvRemote?.release() } }
                    }
                }
                lifecycleScope.launch {
                    var previous: Pair<Int, Int>? = null
                    scrcpy.currentSessionState.collect { session ->
                        val size = session?.let { it.width to it.height }
                        if (size != previous) enqueueTv { release() }
                        previous = size
                    }
                }
            }
        }
        AppScreenOn.register(window)

        registerPipActionReceiver()

        // 声明要画中画
        basicPip.setEnabled(!tvReceiverMode)

        setContent {
            StreamScreen(activity = this)
        }

        /*
        // 可能以后有用
        basicPip.addOnPictureInPictureEventListener(
            executor = mainExecutor,
            listener = object : PictureInPictureDelegate.OnPictureInPictureEventListener {
                override fun onPictureInPictureEvent(
                    event: PictureInPictureDelegate.Event,
                    config: Configuration?,
                ) {
                    // MIUI 只有这些事件
                    when (event) {
                        PictureInPictureDelegate.Event.ENTER_ANIMATION_START -> {}
                        PictureInPictureDelegate.Event.ENTER_ANIMATION_END -> {}

                        PictureInPictureDelegate.Event.STASHED -> {}
                        PictureInPictureDelegate.Event.UNSTASHED -> {}

                        // 收不到
                        // PictureInPictureDelegate.Event.ENTERED -> {}
                        // PictureInPictureDelegate.Event.EXITED -> {}
                    }
                }
            }
        )
         */
    }

    val tvReceiverMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_TV_RECEIVER, false) || isTelevision()
    private val phoneMappingPreferences by lazy { getSharedPreferences("tv_phone_keys", MODE_PRIVATE) }
    private val phoneMappings by lazy { TvPhoneKeyMappings(phoneMappingPreferences.all) }
    private var mappingDialog: AlertDialog? = null
    private var captureDialog: AlertDialog? = null

    val usePhoneAspect = MutableStateFlow(false)
    private var aspectDialog: AlertDialog? = null
    private var tvMenu: AlertDialog? = null
    var tvRemote: TvRemoteController? = null
        private set
    private var lastInputError = -3001L
    private val tvCommands = Channel<suspend TvRemoteController.() -> Unit>(Channel.UNLIMITED)

    private fun enqueueTv(command: suspend TvRemoteController.() -> Unit) {
        if (tvRemote != null) tvCommands.trySend(command)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) enqueueTv { release() }
    }

    override fun onPause() {
        enqueueTv { release() }
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!tvReceiverMode) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) showTvMenu()
            return true
        }
        val mapped = phoneMappings[code]
        if (mapped != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
                enqueueTv { perform(mapped) }
            }
            return true
        }
        if (code in TV_REMOTE_KEYS) {
            val copy = KeyEvent(event)
            enqueueTv { key(copy.action, copy.keyCode, copy.repeatCount, copy.metaState, copy.isCanceled) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showTvMenu() {
        if (tvMenu?.isShowing == true) return
        enqueueTv { release() }
        tvMenu = AlertDialog.Builder(this)
            .setTitle(R.string.tv_playback_menu)
            .setItems(arrayOf(getString(R.string.tv_resume), getString(R.string.tv_phone_back),
                getString(R.string.tv_disconnect), getString(R.string.tv_aspect_ratio),
                getString(if (tvRemote?.pointer?.value?.enabled == true) R.string.tv_navigation_mode else R.string.tv_pointer_mode),
                getString(R.string.tv_start_drag), getString(R.string.tv_phone_home),
                getString(R.string.tv_phone_recents), getString(R.string.tv_phone_mappings))) { _, index ->
                when (index) {
                    3 -> showAspectMenu()
                    4 -> enqueueTv { setPointer(!pointer.value.enabled) }
                    5 -> enqueueTv { toggleDrag() }
                    6 -> enqueueTv { press(KeyEvent.KEYCODE_HOME) }
                    7 -> enqueueTv { press(KeyEvent.KEYCODE_APP_SWITCH) }
                    8 -> showPhoneMappings()
                    1 -> {
                        enqueueTv { press(KeyEvent.KEYCODE_BACK) }
                    }
                    2 -> lifecycleScope.launch {
                        // Stopping publishes a null session and may finish this activity.
                        // Complete transport cleanup even if its lifecycle is cancelled.
                        withContext(NonCancellable) {
                            runCatching { AppRuntime.scrcpy?.stop() }
                            runCatching { NativeAdbService.disconnect() }
                            AppRuntime.currentConnectionTarget = null
                            AppRuntime.currentConnectedDevice = null
                            AppScreenOn.release()
                        }
                        finish()
                    }
                }
            }.create()
        tvMenu?.show()
    }

    private fun savePhoneMappings() {
        phoneMappingPreferences.edit().clear().apply {
            phoneMappings.saved().forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    private fun showPhoneMappings() {
        val actions = TvPhoneAction.entries
        val labels = actions.map { action ->
            val key = phoneMappings.keyFor(action)?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
                ?: getString(R.string.tv_key_unassigned)
            "${getString(action.label)} — $key"
        }.toTypedArray()
        mappingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.tv_phone_mappings)
            .setItems(labels) { _, index -> capturePhoneKey(actions[index]) }
            .setNeutralButton(R.string.tv_reset_mappings) { _, _ ->
                phoneMappings.clear()
                savePhoneMappings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        mappingDialog?.show()
    }

    private fun capturePhoneKey(action: TvPhoneAction) {
        var pressed: Int? = null
        captureDialog = AlertDialog.Builder(this)
            .setTitle(getString(action.label))
            .setMessage(R.string.tv_capture_phone_key)
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { dialog ->
                dialog.setOnKeyListener { _, code, event ->
                    if (!TvPhoneKeyMappings.canAssign(code)) {
                        false
                    } else {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pressed = code
                        if (event.action == KeyEvent.ACTION_UP && pressed == code) {
                            if (!event.isCanceled) {
                                phoneMappings.assign(code, action)
                                savePhoneMappings()
                                android.widget.Toast.makeText(this,
                                    getString(R.string.tv_mapping_saved,
                                        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_"), getString(action.label)),
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                            dialog.dismiss()
                        }
                        true
                    }
                }
                dialog.show()
            }
    }

    private fun showAspectMenu() {
        val density = resources.displayMetrics.density
        val heading = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
            addView(android.widget.TextView(this@StreamActivity).apply {
                setText(R.string.tv_aspect_ratio)
                textSize = 24f
            })
            addView(TvGeometryPattern(this@StreamActivity), android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (180 * density).toInt()))
            addView(android.widget.TextView(this@StreamActivity).apply {
                setText(R.string.tv_geometry_help)
                textSize = 16f
            })
        }
        aspectDialog = AlertDialog.Builder(this)
            .setCustomTitle(heading)
            .setSingleChoiceItems(arrayOf(getString(R.string.tv_aspect_phone),
                getString(R.string.tv_aspect_stream)), if (usePhoneAspect.value) 0 else 1) { dialog, index ->
                usePhoneAspect.value = index == 0
                getSharedPreferences("tv_connection", MODE_PRIVATE).edit()
                    .putBoolean("phone_aspect", index == 0).apply()
                dialog.dismiss()
            }.create()
        aspectDialog?.show()
        aspectDialog?.listView?.requestFocus()
    }

    fun configurePip(block: Builder.() -> Builder) =
        basicPip.setPictureInPictureParams(Builder().block().build())

    override fun onDestroy() {
        currentActivityRef?.get()
            ?.takeIf { it === this }
            ?.let { currentActivityRef = null }
        tvMenu?.dismiss()
        mappingDialog?.dismiss()
        captureDialog?.dismiss()
        aspectDialog?.dismiss()
        tvCommands.close()
        AppScreenOn.unregister(window)
        unregisterPipActionReceiver()
        super.onDestroy()
    }

    /*
    // 回到全屏也会停止, 暂时不做
    override fun onDestroy() {
        super.onDestroy()

        if (_pipModeState.value) {
            Thread {
                runBlocking {
                    AppRuntime.scrcpy?.stop()
                }
            }.start()
        }
    }
     */

    //- onPictureInPictureModeChanged
    //+ onPictureInPictureUiStateChanged
    //- onUserLeaveHint

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)

        _pipModeState.value = true

        /*
        when {
            // 进入画中画
            pipState.isTransitioningToPip -> {}
            // 收进边缘
            pipState.isStashed -> {}
        }
         */
    }

    private fun registerPipActionReceiver() {
        if (isPipActionReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            PictureInPictureActionReceiver.createIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isPipActionReceiverRegistered = true
    }

    private fun unregisterPipActionReceiver() {
        if (!isPipActionReceiverRegistered) return
        unregisterReceiver(pipActionReceiver)
        isPipActionReceiverRegistered = false
    }

    companion object {
        private const val EXTRA_TV_RECEIVER = "tv_receiver"
        private val TV_REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )

        private var currentActivityRef: WeakReference<StreamActivity>? = null

        fun createIntent(context: Context, tvReceiver: Boolean = false): Intent {
            return Intent(context, StreamActivity::class.java).putExtra(EXTRA_TV_RECEIVER, tvReceiver)
        }

        fun dismissActivePictureInPicture() {
            currentActivityRef?.get()
                ?.takeIf { it.isInPictureInPictureMode }
                ?.finish()
        }
    }
}
