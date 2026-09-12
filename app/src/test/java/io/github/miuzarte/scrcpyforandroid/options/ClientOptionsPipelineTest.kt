package io.github.miuzarte.scrcpyforandroid.options

import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.AudioSource
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.CameraFacing
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Codec
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.DisplayImePolicy
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.ListOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Orientation
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.OrientationLock
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.Tick
import io.github.miuzarte.scrcpyforandroid.scrcpy.Shared.VideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the `ClientOptions -> ServerParams -> server command line` pipeline.
 *
 * Every case is asserted twice: against a golden text file (exact argument list and order) and
 * against explicit structural invariants, so that a future rewrite of the mapping table fails even
 * if its author also regenerates the goldens.
 */
class ClientOptionsPipelineTest {

    private fun argsOf(options: ClientOptions): List<String> =
        options.fix().validate().toServerParams(0u).toList(preview = true)

    /**
     * `Scrcpy.listOptions()` builds its server command without `validate()`, so listing cases must
     * bypass it here too (a pure listing has no video/audio/control and validate() rejects that).
     */
    private fun argsWithoutValidation(options: ClientOptions): List<String> =
        options.fix().toServerParams(0u).toList(preview = true)

    private fun assertCase(name: String, options: ClientOptions): List<String> {
        val args = argsOf(options)
        assertStructure(name, args)
        OptionsGoldenFiles.assertMatches(name, args)
        return args
    }

    private fun assertStructure(name: String, args: List<String>) {
        // `preview = true` must hide the scid, which is generated per session.
        assertFalse("$name: scid must not appear in preview args", args.any { it.startsWith("scid=") })
        // The app always tunnels over adb.
        assertTrue("$name: tunnel_forward must always be sent", args.contains("tunnel_forward=true"))
        // Arguments are `key=value` pairs and must not contain shell-breaking characters.
        args.forEach {
            assertTrue("$name: malformed argument [$it]", it.matches(Regex("[A-Za-z0-9_]+=[^ ;'\"`$?*|<>&\\\\]*")))
        }
        // Client-only options never reach the server, however the source comments label them.
        val clientOnly = listOf(
            "render_fit",
            "aspect_ratio",
            "key_inject_mode",
            "fullscreen",
            "turn_screen_off",
            "gamepad",
            "mouse_hover",
            "disable_screensaver",
            "kill_adb_on_close",
            "start_app",
        )
        args.forEach { arg ->
            assertFalse(
                "$name: client-only option leaked to the server: [$arg]",
                clientOnly.any { arg.startsWith("$it=") },
            )
        }
    }

    @Test
    fun defaultOptions() {
        val args = assertCase("default", ClientOptions())
        // Defaults are omitted on purpose, mirroring the upstream client.
        assertEquals(listOf("log_level=info", "tunnel_forward=true"), args)
    }

    @Test
    fun cameraSource() {
        assertCase(
            "camera",
            ClientOptions(
                videoSource = VideoSource.CAMERA,
                // validate() rejects camera-id together with a non-ANY camera-facing, so the case
                // pins the id form; the facing form has its own case below.
                cameraId = "0",
                cameraSize = "1280x720",
                cameraFps = 30u,
                cameraHighSpeed = true,
                cameraTorch = true,
                cameraZoom = "2",
                // fix() must drop display-only options for the camera source.
                maxSize = 1024u,
                crop = "100x100+0+0",
                newDisplay = "1280x720/160",
                maxFps = "60",
            ),
        )
    }

    @Test
    fun cameraAspectRatioReplacesCameraSize() {
        assertCase(
            "camera-ar",
            ClientOptions(
                videoSource = VideoSource.CAMERA,
                cameraAr = "16:9",
                cameraFps = 30u,
            ),
        )
        // camera-size and camera-ar cannot be combined, and high-speed needs an explicit fps.
        assertTrue(
            runCatching {
                argsOf(ClientOptions(videoSource = VideoSource.CAMERA, cameraSize = "1280x720", cameraAr = "16:9"))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                argsOf(ClientOptions(videoSource = VideoSource.CAMERA, cameraHighSpeed = true))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun cameraFacingReplacesCameraId() {
        assertCase(
            "camera-facing",
            ClientOptions(
                videoSource = VideoSource.CAMERA,
                cameraFacing = CameraFacing.FRONT,
            ),
        )
    }

    @Test
    fun audioOnly() {
        assertCase(
            "audio-only",
            ClientOptions(
                video = false,
                audioSource = AudioSource.MIC,
            ),
        )
    }

    @Test
    fun recording() {
        val args = assertCase(
            "recording",
            ClientOptions(
                recordFilename = "/sdcard/Movies/scrcaster.mp4",
                videoBitRate = 8_000_000,
                audioBitRate = 128_000,
                maxFps = "60",
                videoCodec = Codec.H265,
                audioCodec = Codec.AAC,
            ),
        )
        assertTrue(args.any { it.startsWith("video_bit_rate=") })
        assertTrue(args.any { it.startsWith("audio_bit_rate=") })
    }

    @Test
    fun losslessAudioKeepsBitRateSilent() {
        val args = assertCase(
            "recording-flac",
            ClientOptions(
                video = false,
                recordFilename = "/sdcard/Movies/scrcaster.wav",
                recordFormat = ClientOptions.RecordFormat.WAV,
                audioCodec = Codec.FLAC,
                audioBitRate = 128_000,
            ),
        )
        assertFalse(
            "FLAC is lossless, so no bitrate may be forwarded",
            args.any { it.startsWith("audio_bit_rate=") },
        )
    }

    @Test
    fun listEncodersUsesTheSamePipelineAsSessionStart() {
        OptionsGoldenFiles.assertMatches(
            "list-encoders",
            argsWithoutValidation(
                ClientOptions(
                    video = false,
                    audio = false,
                    control = false,
                    cleanup = false,
                    list = ListOptions.ENCODERS,
                ),
            ),
        )
    }

    @Test
    fun listAppsUsesTheSamePipelineAsSessionStart() {
        OptionsGoldenFiles.assertMatches(
            "list-apps",
            argsWithoutValidation(
                ClientOptions(
                    video = false,
                    audio = false,
                    control = false,
                    cleanup = false,
                    list = ListOptions.APPS,
                ),
            ),
        )
    }

    @Test
    fun orientationLockVariants() {
        assertCase(
            "orientation-locked-value",
            ClientOptions(
                captureOrientationLock = OrientationLock.LOCKED_VALUE,
                captureOrientation = Orientation.ORIENT_90,
            ),
        )
        assertCase(
            "orientation-locked-initial",
            ClientOptions(
                captureOrientationLock = OrientationLock.LOCKED_INITIAL,
                captureOrientation = Orientation.ORIENT_90,
            ),
        )
        // An unlocked explicit orientation is still forwarded.
        assertCase(
            "orientation-unlocked-explicit",
            ClientOptions(captureOrientation = Orientation.FLIP_180),
        )
        // Fully default orientation emits nothing.
        assertEquals(
            emptyList<String>(),
            argsOf(ClientOptions(captureOrientationLock = OrientationLock.UNLOCKED))
                .filter { it.startsWith("capture_orientation") },
        )
    }

    @Test
    fun displayAndTuningOptions() {
        assertCase(
            "display-tuning",
            ClientOptions(
                crop = "1080x1920+0+0",
                displayId = 0,
                newDisplay = "1920x1080/320",
                maxSize = 1024u,
                minSizeAlignment = 8u,
                angle = "180",
                screenOffTimeout = Tick.fromMs(30_000),
                displayImePolicy = DisplayImePolicy.FALLBACK,
                showTouches = true,
                stayAwake = true,
                turnScreenOff = true,
                powerOffOnClose = true,
                powerOn = false,
                clipboardAutosync = false,
                downsizeOnError = false,
                audioDup = true,
                vdDestroyContent = false,
                vdSystemDecorations = false,
                keepActive = true,
                ignoreVideoEncoderConstraints = true,
                gamepad = false,
                mouseHover = false,
                legacyPaste = true,
                forwardKeyRepeat = false,
                requireAudio = true,
                disableScreensaver = true,
                fullscreen = true,
                killAdbOnClose = true,
                startApp = "io.github.togo3.scrcaster",
                renderFit = "CROP",
                aspectRatio = 21.0 / 9.0,
            ),
        )
    }

    @Test
    fun flexDisplayIsMutuallyExclusiveWithCrop() {
        val args = assertCase(
            "flex-display",
            ClientOptions(flexDisplay = true, newDisplay = "1920x1080/320"),
        )
        assertTrue("flex_display must be forwarded", args.any { it.startsWith("flex_display=") })
        // ...and the combination is refused before it can reach the server.
        val failure = runCatching { argsOf(ClientOptions(flexDisplay = true, crop = "100x100+0+0")) }
        assertTrue(
            "crop combined with flex-display must be rejected",
            failure.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun disabledPlaybackTurnsOffTheMatchingStream() {
        // validate() drops a stream that neither plays back nor records.
        val args = argsOf(ClientOptions(videoPlayback = false, audioPlayback = false))
        assertTrue(args.contains("video=false"))
        assertTrue(args.contains("audio=false"))
    }

    @Test
    fun serverParamsBuildPrefixesExtraArgs() {
        val command = ClientOptions(videoBitRate = 2_000_000)
            .fix()
            .validate()
            .toServerParams(0x1234u)
            .build("CLASSPATH=/data/local/tmp/scrcpy-server", "app_process")
        assertTrue(command, command.startsWith("CLASSPATH=/data/local/tmp/scrcpy-server app_process "))
        assertTrue(command, command.contains(" scid=1234 "))
        assertTrue(command, command.contains("video_bit_rate=2000000"))
    }

    @Test
    fun illegalCharactersAreRejected() {
        val options = ClientOptions(crop = "100x100;rm -rf /")
        runCatching { options.fix().validate().toServerParams(0u).toList(preview = true) }
            .onSuccess { throw AssertionError("crop with illegal characters must not be accepted: $it") }
            .onFailure { assertTrue(it is IllegalArgumentException) }
    }
}
