package io.github.miuzarte.scrcpyforandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.miuzarte.scrcpyforandroid.pages.MainScreen
import io.github.miuzarte.scrcpyforandroid.password.BiometricGate
import io.github.miuzarte.scrcpyforandroid.password.PasswordRepository
import io.github.miuzarte.scrcpyforandroid.password.hasAuthenticatedOrigin
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.AppScreenOn
import io.github.miuzarte.scrcpyforandroid.storage.Storage.appSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

// 生物认证需要 FragmentActivity
class MainActivity: FragmentActivity() {

    // 主界面方向策略的设置缓存, 仅在主线程读写; 默认值与存储层默认一致
    private var allowLandscapeOnTallPhones = false

    override fun attachBaseContext(newBase: Context) {
        val languageTag = getAppLanguageTag(newBase)
        val wrappedContext =
            if (languageTag.isNotEmpty()) {
                val config = Configuration(newBase.resources.configuration)
                config.setLocale(Locale.forLanguageTag(languageTag))
                newBase.createConfigurationContext(config)
            } else {
                newBase
            }
        super.attachBaseContext(wrappedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTelevision()) {
            startActivity(android.content.Intent(this, TvActivity::class.java))
            finish()
            return
        }
        // no logEvent before context init
        AppRuntime.init(applicationContext)
        AppScreenOn.register(window)

        // 方向策略需读取设置, 必须在 AppRuntime 初始化之后; 后续变更由下方流监听器同步
        allowLandscapeOnTallPhones = appSettings.bundleState.value.allowLandscapeOnTallPhones
        applyMainOrientationPolicy()

        runBlocking {
            PasswordRepository.refresh()
            val cached = getAppLanguageTag(applicationContext)
            if (cached.isNotEmpty()) {
                val bundle = appSettings.loadBundle()
                if (bundle.languageTag != cached) {
                    appSettings.updateBundle { it.copy(languageTag = cached) }
                }
            }
            // 认证不可用时, 清除经认证创建的密码
            if (!BiometricGate.canAuthenticate()) {
                PasswordRepository.getAll()
                    .filter { it.createdWithAuth.hasAuthenticatedOrigin && it.cipherText != null }
                    .forEach { PasswordRepository.markInvalid(it.id) }
            }
        }

        // 请求附近设备/局域网 mDNS 发现所需的运行时权限
        requestNearbyDevicePermissions()

        enableEdgeToEdge()

        // 设置变更后立即重算主界面方向策略, 无需重启 Activity
        lifecycleScope.launch {
            appSettings.bundleState
                .map { it.allowLandscapeOnTallPhones }
                .distinctUntilChanged()
                .collect {
                    allowLandscapeOnTallPhones = it
                    applyMainOrientationPolicy()
                }
        }

        setContent {
            MainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        applyMainOrientationPolicy()
        StreamActivity.dismissActivePictureInPicture()
    }

    override fun onDestroy() {
        AppScreenOn.unregister(window)
        super.onDestroy()
    }

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.w(
                    "MainActivity",
                    "本地网络权限被拒绝，局域网设备发现可能不可用",
                )
            }
        }

    /**
     * 请求 Android 17+ 本地网络访问运行时权限
     * 授权后 NsdManager 才能正常扫描局域网 ADB 设备
     */
    private fun requestNearbyDevicePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return

        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return

        localNetworkPermissionLauncher.launch(permission)
    }

    private fun applyMainOrientationPolicy() {
        // 仅读取已缓存的设置值: TV 路径在 onCreate 中提前 return, 此时存储层尚未初始化
        requestedOrientation = mainUiRequestedOrientation(
            displayAspectRatio = currentDisplayAspectRatio(),
            allowLandscapeOnTallPhones = allowLandscapeOnTallPhones,
        )
    }

    private fun currentDisplayAspectRatio(): Float {
        val bounds =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                windowManager.maximumWindowMetrics.bounds
            else resources.displayMetrics.let { metrics ->
                Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }

        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        return maxOf(width, height).toFloat() / minOf(width, height).toFloat()
    }

    internal companion object {
        private const val LOCALE_PREFS = "locale_cache"
        private const val KEY_LANGUAGE_TAG = "language_tag"

        fun getAppLanguageTag(context: Context) =
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .getString(KEY_LANGUAGE_TAG, "") ?: ""

        fun setAppLanguageTag(context: Context, languageTag: String) =
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .edit { putString(KEY_LANGUAGE_TAG, languageTag) }
    }
}
