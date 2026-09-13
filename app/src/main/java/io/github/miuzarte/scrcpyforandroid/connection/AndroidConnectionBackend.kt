package io.github.miuzarte.scrcpyforandroid.connection

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.nativecore.AdbMdnsDiscoverer
import io.github.miuzarte.scrcpyforandroid.nativecore.pairQrSecret
import io.github.miuzarte.scrcpyforandroid.nativecore.isReachableAdbEndpoint
import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.scrcpy.ScrcpyAspectRatio
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.teardownScrcpySession
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import io.github.miuzarte.scrcpyforandroid.services.DeviceAdbConnectionCoordinator
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import io.github.togo3.scrcaster.core.AspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Keeps the existing preference keys so upgrading the TV does not lose its phone or settings. */
internal class AndroidConnectionPreferences(context: Context) : ConnectionPreferencesStore {
    private val preferences = context.getSharedPreferences("tv_connection", Context.MODE_PRIVATE)
    override fun load(): ConnectionPreferences {
        val host = preferences.getString("host", "").orEmpty()
        val port = preferences.getString("port", "5555")?.toIntOrNull()
        val legacyEndpoint = if (host.isNotBlank() && port != null && port in 1..65535) {
            ConnectionEndpoint(host, port)
        } else null
        val remembered = preferences.getString("devices", null)
            ?.lineSequence()
            ?.mapNotNull(ConnectionEndpoint::parse)
            ?.distinct()
            ?.toList()
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(legacyEndpoint)
        val selected = preferences.getString("selected", null)
            ?.let(ConnectionEndpoint::parse)
            ?.takeIf { it in remembered }
            ?: legacyEndpoint?.takeIf { it in remembered }
            ?: remembered.firstOrNull()
        return ConnectionPreferences(
            lastEndpoint = selected,
            rememberedEndpoints = remembered,
            playback = PlaybackPreferences(
                audio = preferences.getBoolean("audio", true),
                renderFit = preferences.getString("renderFit", "LONG_EDGE")
                    ?.takeIf { it in listOf("FIT", "STRETCH", "CROP", "LONG_EDGE") } ?: "LONG_EDGE",
                aspectRatio = preferences.getString("aspectRatio", "DEVICE")
                    ?.takeIf { name -> AspectRatio.Ratio.entries.any { it.name == name } } ?: "DEVICE",
                customRatio = preferences.getString("aspectRatioCustom", "").orEmpty(),
            ),
        )
    }

    override fun save(preferences: ConnectionPreferences) {
        this.preferences.edit {
            putString("host", preferences.lastEndpoint?.host.orEmpty())
            putString("port", preferences.lastEndpoint?.port?.toString().orEmpty())
            putString("selected", preferences.lastEndpoint?.toString().orEmpty())
            putString("devices", preferences.rememberedEndpoints.joinToString("\n"))
            putBoolean("audio", preferences.playback.audio)
            putString("renderFit", preferences.playback.renderFit)
            putString("aspectRatio", preferences.playback.aspectRatio)
            putString("aspectRatioCustom", preferences.playback.customRatio)
        }
    }
}

internal class AndroidConnectionBackend : PairingConnectionBackend {
    private val coordinator = DeviceAdbConnectionCoordinator()
    private val scrcpy: Scrcpy get() = requireNotNull(AppRuntime.scrcpy)
    override fun isStreaming() = scrcpy.isStarted()
    override fun cancelPendingConnect() = coordinator.cancelPendingConnect()

    override suspend fun connect(endpoint: ConnectionEndpoint, preferences: PlaybackPreferences) {
        Storage.appSettings.loadBundle()
        if (scrcpy.isStarted()) disconnect()
        coordinator.connectWithTimeout(endpoint.host, endpoint.port, 30_000)
        AppRuntime.currentConnectionTarget = ConnectionTarget(endpoint.host, endpoint.port)
        scrcpy.start(ClientOptions(
            maxSize = 1920u, videoBitRate = 8_000_000,
            audio = preferences.audio, audioPlayback = preferences.audio,
            renderFit = preferences.renderFit,
            aspectRatio = ScrcpyAspectRatio.targetRatioFromName(preferences.aspectRatio, preferences.customRatio),
        ))
        AppScreenOn.acquire()
    }

    override suspend fun disconnect() {
        teardownScrcpySession(coordinator, scrcpy)
    }

    override suspend fun pair(endpoint: ConnectionEndpoint, secret: String): Boolean =
        if (secret.matches(Regex("[0-9]{6}"))) coordinator.pair(endpoint.host, endpoint.port, secret)
        else pairQrSecret(secret) { coordinator.pair(endpoint.host, endpoint.port, it) }

    override suspend fun findQrService(name: String) = runInterruptible(Dispatchers.IO) {
        AdbMdnsDiscoverer.discoverQrService(name, 120_000)?.let { ConnectionEndpoint(it.first, it.second) }
    }

    override suspend fun findConnection(host: String) = runInterruptible(Dispatchers.IO) {
        AdbMdnsDiscoverer.discoverConnectForHost(host, 12_000)?.let { ConnectionEndpoint(it.first, it.second) }
    }

    override suspend fun awaitHandoff(onPayload: (String) -> Unit): ConnectionEndpoint? {
        val server = HandoffServer.open() ?: run {
            Log.w("AndroidConnectionBackend", "handoff: server could not start")
            return null
        }
        return try {
            onPayload(server.payload)
            Log.i("AndroidConnectionBackend", "handoff: waiting on ${server.payload}")
            server.awaitEndpoint().also { Log.i("AndroidConnectionBackend", "handoff: received $it") }
        } finally {
            server.close()
        }
    }

    override suspend fun refreshDevices(
        devices: List<ConnectionEndpoint>,
    ): List<ConnectionEndpoint> = coroutineScope {
        val directlyReachable = devices.map { endpoint ->
            async(Dispatchers.IO) {
                endpoint to isReachableAdbEndpoint(endpoint.host, endpoint.port)
            }
        }.awaitAll()
        buildList {
            for ((endpoint, reachable) in directlyReachable) {
                if (reachable) add(endpoint)
                else findConnection(endpoint.host)?.let(::add)
            }
        }
    }
}

internal class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    val controller = ConnectionController(viewModelScope, AndroidConnectionBackend(), AndroidConnectionPreferences(application))
    private var launched = false

    init { viewModelScope.launch { Storage.appSettings.loadBundle() } }

    fun onLaunch(autoReconnect: Boolean) {
        if (launched) return
        launched = true
        if (autoReconnect) controller.reconnect()
    }

    override fun onCleared() { controller.dispose() }
}
