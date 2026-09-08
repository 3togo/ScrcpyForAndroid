package io.github.togo3.scrcaster

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.app.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.togo3.scrcaster.nativecore.AdbMdnsDiscoverer
import io.github.togo3.scrcaster.nativecore.pairQrSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import java.util.UUID
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.*
import androidx.core.content.edit
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.togo3.scrcaster.models.ConnectionTarget
import io.github.togo3.scrcaster.nativecore.NativeAdbService
import io.github.togo3.scrcaster.scrcpy.ClientOptions
import io.github.togo3.scrcaster.scrcpy.Scrcpy
import io.github.togo3.scrcaster.scrcpy.ScrcpyAspectRatio
import io.github.togo3.scrcaster.core.AspectRatio
import io.github.togo3.scrcaster.services.AppRuntime
import io.github.togo3.scrcaster.services.AppScreenOn
import io.github.togo3.scrcaster.services.DeviceAdbConnectionCoordinator
import io.github.togo3.scrcaster.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small, remote-first front end sharing the upstream ADB and streaming implementation. */
class TvActivity : FragmentActivity() {
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private lateinit var pair: Button
    private lateinit var disconnect: Button
    private lateinit var qr: Button
    private var qrDialog: Dialog? = null
    private var qrJob: Job? = null
    private var manualDialog: Dialog? = null
    private lateinit var audio: CheckBox
    private lateinit var fill: Button
    private lateinit var ratio: Button
    private lateinit var ratioCustom: EditText
    private val fillModes = listOf("FIT", "STRETCH", "CROP", "LONG_EDGE")
    private var renderFit: String = "LONG_EDGE"
    private var ratioIndex: Int = 0
    private val coordinator = DeviceAdbConnectionCoordinator()
    private val preferences by lazy { getSharedPreferences("tv_connection", MODE_PRIVATE) }
    private val scrcpy: Scrcpy get() = AppRuntime.scrcpy!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        AppRuntime.init(applicationContext)
        if (AppRuntime.scrcpy == null) AppRuntime.scrcpy = Scrcpy(applicationContext)
        AppScreenOn.register(window)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.CINNAMON_BUN &&
            checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK), 1)
        }
        // These hold the selected endpoint; setup fields live only in the manual dialog.
        host = field(R.string.tv_host, preferences.getString("host", "") ?: "")
        port = field(R.string.tv_port, preferences.getString("port", "5555") ?: "5555", true)
        val panel = column()
        panel.setPadding(dp(64), dp(36), dp(64), dp(36))
        panel.addView(label(R.string.tv_title, 30f))
        panel.addView(label(R.string.tv_home_help, 20f))
        qr = button(R.string.tv_qr_start) { showQrPairing() }
        panel.addView(qr)
        pair = button(R.string.tv_manual_options) { showManualOptions() }
        panel.addView(pair)
        connect = button(R.string.tv_reconnect) { connect() }
        connect.visibility = if (host.text.isBlank()) View.GONE else View.VISIBLE
        panel.addView(connect)
        audio = CheckBox(this).apply {
            setText(R.string.tv_audio)
            textSize = 18f
            setTextColor(Color.WHITE)
            isChecked = preferences.getBoolean("audio", true)
            setOnCheckedChangeListener { _, checked -> preferences.edit { putBoolean("audio", checked) } }
        }
        panel.addView(audio)
        renderFit = preferences.getString("renderFit", "LONG_EDGE") ?: "LONG_EDGE"
        ratioIndex = ScrcpyAspectRatio.presets
            .indexOfFirst {
                it.name == preferences.getString("aspectRatio", AspectRatio.Ratio.DEVICE.name)
            }
            .coerceAtLeast(0)
        fill = button(R.string.tv_fullscreen_fill) { cycleFill() }
        fill.text = fillLabel()
        panel.addView(fill)
        ratio = button(R.string.tv_video_ratio) { cycleRatio() }
        ratio.text = ratioLabel()
        panel.addView(ratio)
        ratioCustom = field(R.string.scrcpyopt_aspect_ratio_custom,
            preferences.getString("aspectRatioCustom", "") ?: "")
        ratioCustom.inputType = InputType.TYPE_CLASS_TEXT
        ratioCustom.visibility =
            if (ScrcpyAspectRatio.presets[ratioIndex] == AspectRatio.Ratio.CUSTOM) View.VISIBLE
            else View.GONE
        panel.addView(ratioCustom)
        disconnect = button(R.string.tv_disconnect) {
            runOperation { disconnectSession(); status.setText(R.string.tv_disconnected) }
        }
        disconnect.visibility = if (scrcpy.isStarted()) View.VISIBLE else View.GONE
        panel.addView(disconnect)
        status = label(R.string.tv_home_ready, 18f)
        panel.addView(status)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(18, 27, 43)); addView(panel) })
        linkFocus(focusOrder())
        if (connect.visibility == View.VISIBLE) connect.requestFocus() else qr.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            if (scrcpy.isStarted()) runOperation {
                disconnectSession()
                finish()
            } else finish()
        }
        if (savedInstanceState == null && connect.visibility == View.VISIBLE) {
            // A TV receiver normally belongs to one phone. Reconnect to the saved endpoint on
            // launch; Back from playback still exposes this screen for pairing another phone.
            panel.post { connect() }
        }
    }

    private fun field(hint: Int, value: String, numeric: Boolean = false) = EditText(this).apply {
        id = View.generateViewId()
        setHint(hint)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(170, 187, 208))
        contentDescription = getString(hint)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setText(value)
        foreground = getDrawable(R.drawable.tv_focus_outline)
    }

    private fun connect() {
        val address = host.text.toString().trim()
        if (scrcpy.isStarted()) {
            startActivity(StreamActivity.createIntent(this, tvReceiver = true))
            return
        }
        val number = port.text.toString().toIntOrNull()
        if (address.isBlank() || number == null || number !in 1..65535) {
            status.setText(R.string.tv_invalid_address)
            return
        }
        if (scrcpy.isStarted()) {
            startActivity(StreamActivity.createIntent(this, tvReceiver = true))
            return
        }
        preferences.edit {
            putString("host", address)
            putString("port", number.toString())
            putBoolean("audio", audio.isChecked)
            putString("renderFit", renderFit)
            putString("aspectRatio", ScrcpyAspectRatio.presets[ratioIndex].name)
            putString("aspectRatioCustom", ratioCustom.text.toString())
        }
        runOperation {
            status.setText(R.string.tv_connecting)
            try {
                Storage.appSettings.loadBundle()
                coordinator.connectWithTimeout(address, number, 30_000)
                AppRuntime.currentConnectionTarget = ConnectionTarget(address, number)
                scrcpy.start(ClientOptions(maxSize = 1920u, videoBitRate = 8_000_000,
                    audio = audio.isChecked, audioPlayback = audio.isChecked,
                    renderFit = renderFit,
                    aspectRatio = ScrcpyAspectRatio.targetRatioFromName(
                        ScrcpyAspectRatio.presets[ratioIndex].name,
                        ratioCustom.text.toString(),
                    )))
                AppScreenOn.acquire()
                status.setText(R.string.tv_connected)
                startActivity(StreamActivity.createIntent(this@TvActivity, tvReceiver = true))
            } catch (error: Exception) {
                withContext(NonCancellable) { disconnectSession() }
                if (generateSequence<Throwable>(error) { it.cause }.any {
                        it.message?.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) == true
                    }) {
                    status.setText(R.string.tv_pair_required)
                    showQrPairing()
                } else {
                    throw error
                }
            }
        }
    }

    private fun focusOrder() = listOf(qr, pair, connect, audio, fill, ratio, ratioCustom, disconnect)
        .filter { it.visibility == View.VISIBLE }

    private fun fillLabel(): String {
        val label = when (renderFit) {
            "STRETCH" -> getString(R.string.scrcpyopt_render_fit_stretch)
            "CROP" -> getString(R.string.scrcpyopt_render_fit_crop)
            "LONG_EDGE" -> getString(R.string.scrcpyopt_render_fit_long_edge)
            else -> getString(R.string.scrcpyopt_render_fit_fit)
        }
        return getString(R.string.tv_fullscreen_fill, label)
    }

    private fun cycleFill() {
        renderFit = fillModes[(fillModes.indexOf(renderFit).coerceAtLeast(0) + 1) % fillModes.size]
        preferences.edit { putString("renderFit", renderFit) }
        fill.text = fillLabel()
        linkFocus(focusOrder())
    }

    private fun ratioLabel() =
        getString(R.string.tv_video_ratio, ScrcpyAspectRatio.presets[ratioIndex].toString())

    private fun cycleRatio() {
        ratioIndex = (ratioIndex + 1) % ScrcpyAspectRatio.presets.size
        preferences.edit { putString("aspectRatio", ScrcpyAspectRatio.presets[ratioIndex].name) }
        ratio.text = ratioLabel()
        ratioCustom.visibility =
            if (ScrcpyAspectRatio.presets[ratioIndex] == AspectRatio.Ratio.CUSTOM) View.VISIBLE
            else View.GONE
        linkFocus(focusOrder())
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(18, 27, 43))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }

    private fun label(resource: Int, size: Float = 18f) = TextView(this).apply {
        setText(resource)
        setTextColor(Color.rgb(238, 243, 250))
        textSize = size
        setPadding(0, dp(4), 0, dp(12))
    }

    private fun button(resource: Int, action: () -> Unit) = Button(this).apply {
        id = View.generateViewId()
        setText(resource)
        textSize = 18f
        isAllCaps = false
        backgroundTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.rgb(36, 215, 196), Color.rgb(43, 59, 80)))
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.rgb(12, 29, 37), Color.WHITE)))
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    /** Vertical, explicit navigation, including editable fields and all dialog actions. */
    private fun linkFocus(views: List<View>) {
        views.forEachIndexed { index, view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.foreground = getDrawable(R.drawable.tv_focus_outline)
            val previous = views[(index + views.size - 1) % views.size]
            val next = views[(index + 1) % views.size]
            // Assign IDs before wiring; some platform controls start without an ID.
            if (previous.id == View.NO_ID) previous.id = View.generateViewId()
            if (next.id == View.NO_ID) next.id = View.generateViewId()
            view.nextFocusUpId = previous.id
            view.nextFocusDownId = next.id
            view.nextFocusForwardId = next.id
            view.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val direction = if (key == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        (1..views.size).asSequence()
                            .map { views[(index + direction * it + views.size) % views.size] }
                            .firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }?.requestFocus()
                    }
                    true
                } else false
            }
            if (view is EditText) {
                view.imeOptions = EditorInfo.IME_ACTION_DONE
                view.setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_NEXT) {
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(view.windowToken, 0)
                        next.requestFocus()
                        true
                    } else false
                }
            }
        }
    }

    private fun showDialog(panel: View, focus: View): Dialog = Dialog(this).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(ScrollView(this@TvActivity).apply { setBackgroundColor(Color.rgb(18, 27, 43)); addView(panel) })
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        show()
        window?.setLayout((resources.displayMetrics.widthPixels * 0.78).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        focus.requestFocus()
    }

    private fun showManualOptions() {
        val panel = column()
        panel.addView(label(R.string.tv_manual_options, 24f))
        panel.addView(label(R.string.tv_manual_help))
        lateinit var dialog: Dialog
        val pairing = button(R.string.tv_code_start) { dialog.dismiss(); showPairing() }
        val address = button(R.string.tv_address_start) { dialog.dismiss(); showConnectionAddress() }
        val back = button(R.string.tv_back) { dialog.dismiss() }
        listOf(pairing, address, back).forEach { panel.addView(it) }
        linkFocus(listOf(pairing, address, back))
        dialog = showDialog(panel, pairing)
        manualDialog = dialog
    }

    private fun showPairing() = showAddressForm(true)
    private fun showConnectionAddress() = showAddressForm(false)

    private fun showAddressForm(pairing: Boolean) {
        val panel = column()
        panel.addView(label(if (pairing) R.string.tv_code_start else R.string.tv_address_start, 24f))
        panel.addView(label(if (pairing) R.string.tv_code_help else R.string.tv_address_help))
        val endpoint = field(R.string.tv_full_address,
            if (!pairing && host.text.isNotBlank()) "${host.text}:${port.text}" else "")
        panel.addView(label(R.string.tv_full_address))
        panel.addView(endpoint)
        val code = field(R.string.tv_pair_code, "", true)
        if (pairing) {
            panel.addView(label(R.string.tv_pair_code))
            panel.addView(code)
        }
        val feedback = label(R.string.tv_form_help)
        panel.addView(feedback)
        lateinit var dialog: Dialog
        lateinit var submit: Button
        val cancel = button(R.string.tv_back) { dialog.dismiss() }
        val useQr = button(R.string.tv_qr_instead) { dialog.dismiss(); showQrPairing() }
        submit = button(if (pairing) R.string.tv_pair_and_connect else R.string.tv_connect_now) {
            val target = parseTvEndpoint(endpoint.text.toString())
            val digits = code.text.toString().trim()
            if (target == null) {
                feedback.setText(R.string.tv_invalid_full_address)
                endpoint.requestFocus()
            } else if (pairing && !digits.matches(Regex("[0-9]{6}"))) {
                feedback.setText(R.string.tv_invalid_pairing)
                code.requestFocus()
            } else if (!pairing) {
                host.setText(target.first)
                port.setText(target.second.toString())
                dialog.dismiss()
                connect()
            } else {
                submit.isEnabled = false
                useQr.isEnabled = false
                feedback.setText(R.string.tv_pairing)
                val job = lifecycleScope.launch {
                    try {
                        check(withContext(Dispatchers.IO) {
                            NativeAdbService.pair(target.first, target.second, digits)
                        }) { getString(R.string.tv_pair_failed) }
                        val connection = runInterruptible(Dispatchers.IO) {
                            AdbMdnsDiscoverer.discoverConnectForHost(target.first, 12_000)
                        }
                        ensureActive()
                        host.setText(target.first)
                        dialog.setOnDismissListener(null)
                        dialog.dismiss()
                        if (connection != null) {
                            port.setText(connection.second.toString())
                            connect()
                        } else {
                            port.setText("")
                            showConnectionAddress()
                        }
                    } catch (error: CancellationException) { throw error
                    } catch (error: Exception) {
                        feedback.text = getString(R.string.tv_error, error.message ?: "Pairing failed")
                        submit.isEnabled = true
                        useQr.isEnabled = true
                        submit.requestFocus()
                    }
                }
                dialog.setOnDismissListener { job.cancel() }
            }
        }
        listOf(submit, useQr, cancel).forEach { panel.addView(it) }
        linkFocus(listOfNotNull(endpoint, code.takeIf { pairing }, submit, useQr, cancel))
        dialog = showDialog(panel, endpoint)
        manualDialog = dialog
    }

    private fun showQrPairing() {
        qrDialog?.dismiss()
        val name = "studio-" + UUID.randomUUID().toString().replace("-", "")
        val secret = UUID.randomUUID().toString().replace("-", "")
        val matrix = QRCodeWriter().encode("WIFI:T:ADB;S:$name;P:$secret;;", BarcodeFormat.QR_CODE, 480, 480)
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(480 * 480) { index ->
            if (matrix[index % 480, index / 480]) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, 480, 0, 0, 480, 480)
        val message = TextView(this).apply {
            setText(R.string.tv_qr_instructions)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val panel = column()
        panel.addView(label(R.string.tv_qr_pair, 24f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            contentDescription = getString(R.string.tv_qr_pair)
        }, LinearLayout.LayoutParams(dp(260), dp(260)))
        val actions = column()
        actions.addView(message)
        lateinit var dialog: Dialog
        val retry = button(R.string.tv_qr_retry) { dialog.dismiss(); showQrPairing() }
        val manual = button(R.string.tv_code_start) { dialog.dismiss(); showPairing() }
        val cancel = button(R.string.tv_back) { dialog.dismiss() }
        listOf(retry, manual, cancel).forEach { actions.addView(it) }
        row.addView(actions, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        linkFocus(listOf(retry, manual, cancel))
        dialog = showDialog(panel, cancel)
        qrDialog = dialog
        dialog.setOnDismissListener {
            qrJob?.cancel()
            qrJob = null
            qrDialog = null
            qr.requestFocus()
        }
        qrJob = lifecycleScope.launch {
            try {
                val endpoint = runInterruptible(Dispatchers.IO) {
                    AdbMdnsDiscoverer.discoverQrService(name, 120_000)
                } ?: error(getString(R.string.tv_qr_timeout))
                message.setText(R.string.tv_pairing)
                check(withContext(Dispatchers.IO) {
                    pairQrSecret(secret) { password ->
                        NativeAdbService.pair(endpoint.first, endpoint.second, password)
                    }
                }) { getString(R.string.tv_pair_failed) }
                ensureActive()
                host.setText(endpoint.first)
                message.setText(R.string.tv_qr_finding_port)
                val connection = runInterruptible(Dispatchers.IO) {
                    AdbMdnsDiscoverer.discoverConnectForHost(endpoint.first, 12_000)
                }
                ensureActive()
                // Clear the job before dismissal so successful pairing can continue.
                qrJob = null
                dialog.dismiss()
                if (connection != null) {
                    port.setText(connection.second.toString())
                    connect()
                } else {
                    preferences.edit { putString("host", endpoint.first) }
                    status.setText(R.string.tv_paired)
                    port.setText("")
                    showConnectionAddress()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = getString(R.string.tv_error, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        qr.isEnabled = false
        connect.isEnabled = false
        pair.isEnabled = false
        disconnect.isEnabled = false
        lifecycleScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = getString(R.string.tv_error, error.message ?: error.javaClass.simpleName)
            } finally {
                qr.isEnabled = true
                connect.isEnabled = true
                pair.isEnabled = true
                disconnect.isEnabled = true
                refreshHome()
            }
        }
    }

    private suspend fun disconnectSession() {
        try { scrcpy.stop() } finally {
            runCatching { coordinator.disconnect() }
            AppRuntime.currentConnectionTarget = null
            AppRuntime.currentConnectedDevice = null
            AppScreenOn.release()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::qr.isInitialized) refreshHome()
    }

    private fun refreshHome() {
        connect.visibility = if (host.text.isNotBlank()) View.VISIBLE else View.GONE
        connect.setText(if (scrcpy.isStarted()) R.string.tv_resume else R.string.tv_reconnect)
        disconnect.visibility = if (scrcpy.isStarted()) View.VISIBLE else View.GONE
        linkFocus(focusOrder())
    }

    override fun onStop() {
        qrDialog?.dismiss()
        manualDialog?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        AppScreenOn.unregister(window)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
