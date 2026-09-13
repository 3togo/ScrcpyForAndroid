package io.github.miuzarte.scrcpyforandroid.nativecore

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.togo3.scrcaster.core.AspectRatio
import io.github.miuzarte.scrcpyforandroid.scrcpy.videoCrop
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Decoder always renders into a persistent SurfaceTexture-backed Surface.
 * UI surfaces are display-only targets fed from that persistent texture via EGL.
 */
class PersistentVideoRenderer {
    private val tag = "PersistentVideoRenderer"
    private val renderThread = HandlerThread("PersistentVideoRenderer").apply { start() }
    private val handler = Handler(renderThread.looper)

    @Volatile
    private var initialized = false

    @Volatile
    private var released = false

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglPbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displayEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: Surface? = null
    private var displaySurfaceId: Int? = null
    private var recordEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recordSurface: Surface? = null
    private var recordSurfaceId: Int? = null
    private var recordWidth: Int = 0
    private var recordHeight: Int = 0
    private var onRecordFrameRendered: ((Long) -> Unit)? = null

    private var oesTextureId = 0
    private var decoderSurfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val frameAvailableCount = AtomicLong(0)
    private val frameConsumedCount = AtomicLong(0)
    private val frameRenderedCount = AtomicLong(0)

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var stMatrixHandle = 0
    private var samplerHandle = 0
    private var texRectHandle = 0

    /** How the mirrored video is fitted into the render surface. */
    private enum class Fit { FIT, STRETCH, CROP, LONG_EDGE }

    @Volatile
    private var fitMode = Fit.LONG_EDGE
    /** Target display aspect ratio (width/height) to crop the video to; 0.0 keeps the device ratio. */
    @Volatile
    private var aspectTarget = 0.0
    /** Receiver surface aspect ratio, used by both crop-to-fill modes in Device mode. */
    @Volatile
    private var displayAspect = 0.0
    /** Current decoded video frame size, used for aspect-ratio cropping. */
    @Volatile
    private var videoW = 0
    @Volatile
    private var videoH = 0

    private val initLock = Any()

    fun getDecoderSurface(): Surface {
        ensureInitialized()
        synchronized(initLock) {
            check(!released) { "renderer already released" }
            return requireNotNull(decoderSurface) { "decoderSurface not initialized" }
        }
    }

    /**
     * Atomically tear down and recreate the decoder-side surface (SurfaceTexture + OES texture
     * + Surface), returning a fresh [Surface] for a new MediaCodec to render into.
     *
     * This is needed because some OMX decoders (notably MTK) fail when a new codec is configured
     * against a surface that still has pending buffers from a previously released codec.
     * By recreating the underlying SurfaceTexture + texture, the new codec gets a clean buffer
     * queue with no leftover state.
     *
     * - Must be called after the old decoder has been released, so no producer is writing to
     *   the old surface at the same time.
     * - Runs on the renderer HandlerThread (via [handler]) so it is serialized with [drawFrame].
     * - EGL context, display/record EGL surfaces, and the shader program are preserved; only
     *   `oesTextureId` / `decoderSurfaceTexture` / `decoderSurface` are recreated.
     */
    fun recreateDecoderSurface(): Surface {
        ensureInitialized()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Surface? = null
        val failure = AtomicReference<Throwable?>()
        val posted = handler.post {
            try {
                if (released) {
                    error("renderer already released")
                }
                recreateDecoderSurfaceLocked()
                result = decoderSurface
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        check(posted) { "renderer thread is not accepting work" }
        latch.await()
        failure.get()?.let { throw it }
        return result ?: error("failed to recreate decoder surface")
    }

    private fun recreateDecoderSurfaceLocked() {
        Log.i(tag, "recreateDecoderSurfaceLocked(): rebuilding decoder surface")

        // Tear down old decoder-side resources. drawFrame() won't run concurrently
        // because we're on the same HandlerThread.
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        runCatching { decoderSurfaceTexture?.release() }
        decoderSurfaceTexture = null
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }

        // Recreate from scratch (mirrors initializeLocked lines 243-259)
        oesTextureId = createExternalTexture()
        decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener(
                {
                    val n = frameAvailableCount.incrementAndGet()
                    if (n == 1L || n % 120L == 0L) {
                        Log.i(
                            tag,
                            "onFrameAvailable(): available=$n consumed=${frameConsumedCount.get()} rendered=${frameRenderedCount.get()} display=${displaySurfaceId != null}",
                        )
                    }
                    drawFrame()
                },
                handler,
            )
        }
        decoderSurface = Surface(decoderSurfaceTexture)
        Log.i(tag, "recreateDecoderSurfaceLocked(): decoder surface rebuilt, texture=$oesTextureId")
    }

    fun attachDisplaySurface(surface: Surface) {
        ensureInitialized()
        val newId = System.identityHashCode(surface)
        if (displaySurfaceId == newId) return
        Log.i(tag, "attachDisplaySurface(): request surfaceId=$newId old=${displaySurfaceId}")
        handler.post {
            if (released || !surface.isValid) return@post
            releaseDisplaySurfaceLocked()
            displaySurface = surface
            displaySurfaceId = newId
            displayEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            Log.i(tag, "attachDisplaySurface(): attached surfaceId=$newId")
            drawFrame()
        }
    }

    fun detachDisplaySurface(surface: Surface? = null, releaseSurface: Boolean = false) {
        val requestId = surface?.let { System.identityHashCode(it) }
        Log.i(
            tag,
            "detachDisplaySurface(): request surfaceId=$requestId releaseSurface=$releaseSurface current=${displaySurfaceId}",
        )
        handler.post {
            if (released) return@post
            if (requestId != null && requestId != displaySurfaceId) return@post
            releaseDisplaySurfaceLocked()
            if (releaseSurface) {
                runCatching { surface?.release() }
            }
        }
    }

    fun attachRecordSurface(
        surface: Surface,
        width: Int,
        height: Int,
        onFrameRendered: ((Long) -> Unit)? = null,
    ) {
        ensureInitialized()
        val newId = System.identityHashCode(surface)
        if (recordSurfaceId == newId &&
            recordWidth == width &&
            recordHeight == height
        ) {
            onRecordFrameRendered = onFrameRendered
            return
        }
        Log.i(tag, "attachRecordSurface(): request surfaceId=$newId size=${width}x$height")
        handler.post {
            if (released || !surface.isValid) return@post
            releaseRecordSurfaceLocked()
            recordSurface = surface
            recordSurfaceId = newId
            recordWidth = width.coerceAtLeast(1)
            recordHeight = height.coerceAtLeast(1)
            onRecordFrameRendered = onFrameRendered
            recordEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            Log.i(tag, "attachRecordSurface(): attached surfaceId=$newId")
            drawFrame()
        }
    }

    fun detachRecordSurface(surface: Surface? = null, releaseSurface: Boolean = false) {
        val requestId = surface?.let { System.identityHashCode(it) }
        Log.i(
            tag,
            "detachRecordSurface(): request surfaceId=$requestId releaseSurface=$releaseSurface current=$recordSurfaceId",
        )
        handler.post {
            if (released) return@post
            if (requestId != null && requestId != recordSurfaceId) return@post
            releaseRecordSurfaceLocked()
            if (releaseSurface) {
                runCatching { surface?.release() }
            }
        }
    }

    /** Set how the video is fitted into the surface. */
    fun setFitMode(mode: String) {
        fitMode = when (mode.uppercase()) {
            "STRETCH" -> Fit.STRETCH
            "CROP" -> Fit.CROP
            "LONG_EDGE" -> Fit.LONG_EDGE
            else -> Fit.FIT
        }
        requestRedraw()
    }

    /** Set the target display aspect ratio (width/height). 0.0 (or <=0) keeps the device ratio. */
    fun setAspectRatio(target: Double) {
        aspectTarget = if (target.isFinite() && target > 0) target else 0.0
        requestRedraw()
    }

    fun setDisplayAspectRatio(aspect: Double) {
        val valid = if (aspect.isFinite() && aspect > 0) aspect else 0.0
        if (displayAspect == valid) return
        displayAspect = valid
        requestRedraw()
    }

    /**
     * Visible source rectangle (sx, sy, sw, sh) in decoded-frame pixels for the current
     * aspect-ratio target, centred. Returns the full frame when no target is set.
     */
    fun sourceCrop(): IntArray {
        if (videoW <= 0 || videoH <= 0) return intArrayOf(0, 0, 0, 0)
        val target = when {
            aspectTarget > 0 -> aspectTarget
            fitMode == Fit.CROP || fitMode == Fit.LONG_EDGE -> displayAspect
            else -> 0.0
        }
        // Both explicit presets and the receiver ratio follow the mirrored source orientation.
        // A landscape TV ratio therefore becomes portrait when the phone is portrait.
        val oriented = AspectRatio.orientToSource(target, videoW, videoH)
        val crop = videoCrop(videoW, videoH, oriented)
        return intArrayOf(crop.x, crop.y, crop.width, crop.height)
    }

    /** Size (width, height) of [sourceCrop]; 0,0 while the frame size is unknown. */
    fun croppedSize(): IntArray {
        val crop = sourceCrop()
        return intArrayOf(crop[2], crop[3])
    }

    /** Report the decoded video frame size; used to crop the source to the target aspect ratio. */
    fun setVideoSize(width: Int, height: Int) {
        videoW = width.coerceAtLeast(0)
        videoH = height.coerceAtLeast(0)
        requestRedraw()
    }

    /** Ask the render thread to redraw the current frame (e.g. after an option change). */
    fun requestRedraw() {
        if (released) return
        handler.post { drawFrame() }
    }

    fun release() {
        synchronized(initLock) {
            if (released) return
            released = true
            if (!handler.post {
                    releaseResourcesLocked()
                    renderThread.quitSafely()
                }
            ) {
                renderThread.quitSafely()
            }
        }
    }

    private fun ensureInitialized() {
        check(!released) { "renderer already released" }
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            check(!released) { "renderer already released" }
            val latch = java.util.concurrent.CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val posted = handler.post {
                try {
                    initializeLocked()
                    initialized = true
                } catch (error: Throwable) {
                    failure.set(error)
                    released = true
                    releaseResourcesLocked()
                    renderThread.quitSafely()
                } finally {
                    latch.countDown()
                }
            }
            check(posted) { "renderer thread is not accepting work" }
            latch.await()
            failure.get()?.let { throw it }
        }
    }

    /** Must run on [renderThread] while its EGL context can still be made current. */
    private fun releaseResourcesLocked() {
        releaseDisplaySurfaceLocked()
        releaseRecordSurfaceLocked()
        runCatching { decoderSurface?.release() }
        decoderSurface = null
        runCatching { decoderSurfaceTexture?.release() }
        decoderSurfaceTexture = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglPbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglPbufferSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglPbufferSurface = EGL14.EGL_NO_SURFACE
        eglConfig = null
    }

    private fun initializeLocked() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1))

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 4,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0))
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(0x3098, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        eglPbufferSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay,
            eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(eglPbufferSurface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext))

        oesTextureId = createExternalTexture()
        decoderSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener(
                {
                    val n = frameAvailableCount.incrementAndGet()
                    if (n == 1L || n % 120L == 0L) {
                        Log.i(
                            tag,
                            "onFrameAvailable(): available=$n consumed=${frameConsumedCount.get()} rendered=${frameRenderedCount.get()} display=${displaySurfaceId != null}",
                        )
                    }
                    drawFrame()
                },
                handler,
            )
        }
        decoderSurface = Surface(decoderSurfaceTexture)
        Log.i(tag, "initializeLocked(): decoder surface created")

        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.rotateM(mvpMatrix, 0, 180f, 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uStMatrix")
        samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")
        texRectHandle = GLES20.glGetUniformLocation(program, "uTexRect")
    }

    private fun drawFrame() {
        if (released) return
        val surfaceTexture = decoderSurfaceTexture ?: return
        if (eglPbufferSurface == EGL14.EGL_NO_SURFACE) return

        // Always consume decoder frames on the persistent context, even if there is no
        // visible output surface right now. Otherwise the producer side can stall.
        EGL14.eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext)
        runCatching { surfaceTexture.updateTexImage() }
            .onSuccess {
                val consumed = frameConsumedCount.incrementAndGet()
                if (consumed == 1L || consumed % 120L == 0L) {
                    Log.i(
                        tag,
                        "drawFrame(): consumed=$consumed available=${frameAvailableCount.get()} rendered=${frameRenderedCount.get()} display=${displaySurfaceId != null}",
                    )
                }
            }
            .onFailure { Log.w(tag, "updateTexImage failed", it) }
        surfaceTexture.getTransformMatrix(stMatrix)
        val frameTimestampNs = surfaceTexture.timestamp

        if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
            renderToSurface(
                eglSurface = recordEglSurface,
                width = recordWidth,
                height = recordHeight,
                presentationTimeNs = frameTimestampNs,
                applyDisplayTransform = false,
            )
            onRecordFrameRendered?.invoke(frameTimestampNs)
        }

        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            val width = IntArray(1)
            val height = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_WIDTH, width, 0)
            EGL14.eglQuerySurface(eglDisplay, displayEglSurface, EGL14.EGL_HEIGHT, height, 0)
            renderToSurface(
                eglSurface = displayEglSurface,
                width = width[0].coerceAtLeast(1),
                height = height[0].coerceAtLeast(1),
            )
            val rendered = frameRenderedCount.incrementAndGet()
            if (rendered == 1L || rendered % 120L == 0L) {
                Log.i(
                    tag,
                    "drawFrame(): rendered=$rendered consumed=${frameConsumedCount.get()} available=${frameAvailableCount.get()} viewport=${width[0]}x${height[0]}",
                )
            }
        }
    }

    private fun releaseDisplaySurfaceLocked() {
        if (displayEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, displayEglSurface)
            displayEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (displaySurfaceId != null) {
            Log.i(tag, "releaseDisplaySurfaceLocked(): surfaceId=$displaySurfaceId")
        }
        displaySurface = null
        displaySurfaceId = null
    }

    private fun releaseRecordSurfaceLocked() {
        if (recordEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, recordEglSurface)
            recordEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (recordSurfaceId != null) {
            Log.i(tag, "releaseRecordSurfaceLocked(): surfaceId=$recordSurfaceId")
        }
        recordSurface = null
        recordSurfaceId = null
        recordWidth = 0
        recordHeight = 0
        onRecordFrameRendered = null
    }

    private fun renderToSurface(
        eglSurface: EGLSurface,
        width: Int,
        height: Int,
        presentationTimeNs: Long? = null,
        applyDisplayTransform: Boolean = true,
    ) {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Recording should capture the raw frame; display fit/aspect only applies to the live view.
        val transform = if (applyDisplayTransform) computeTransform(w, h) else Transform(0f, 0f, 1f, 1f, mvpMatrix)
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, transform.mvp, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniform4f(texRectHandle, transform.u0, transform.v0, transform.u1, transform.v1)
        GLES20.glUniform1i(samplerHandle, 0)

        VERTICES.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, VERTICES)
        GLES20.glEnableVertexAttribArray(positionHandle)
        VERTICES.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, VERTICES)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        presentationTimeNs?.let { EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, it) }
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private data class Transform(
        val u0: Float,
        val v0: Float,
        val u1: Float,
        val v1: Float,
        val mvp: FloatArray,
    )

    /**
     * Compute the texture sub-rectangle (u0,v0,u1,v1) and the clip-space MVP for the current
     * aspect-ratio + fit mode. The video is first cropped (in normalized texture space) to the
     * target aspect ratio, then fitted / stretched / cropped into the [surfaceW]x[surfaceH] surface.
     */
    private fun computeTransform(surfaceW: Int, surfaceH: Int): Transform {
        if (videoW <= 0 || videoH <= 0) {
            return Transform(0f, 0f, 1f, 1f, mvpMatrix)
        }
        // 1) Crop the source to the chosen aspect ratio (centered sub-rectangle). The target
        // follows the source orientation, so portrait video remains portrait.
        val crop = sourceCrop()
        if (crop[2] <= 0 || crop[3] <= 0) {
            return Transform(0f, 0f, 1f, 1f, mvpMatrix)
        }
        val sx = crop[0]
        val sy = crop[1]
        val sw = crop[2]
        val sh = crop[3]
        val u0 = sx.toFloat() / videoW
        val v0 = sy.toFloat() / videoH
        val u1 = (sx + sw).toFloat() / videoW
        val v1 = (sy + sh).toFloat() / videoH

        // 2) Fit / stretch / crop the sub-rectangle into the surface (centered).
        val (rectW, rectH, rectX, rectY) = when (fitMode) {
            Fit.STRETCH -> listOf(surfaceW.toFloat(), surfaceH.toFloat(), 0f, 0f)
            Fit.FIT -> {
                val s = minOf(surfaceW.toFloat() / sw, surfaceH.toFloat() / sh)
                val rw = sw * s
                val rh = sh * s
                listOf(rw, rh, (surfaceW - rw) / 2f, (surfaceH - rh) / 2f)
            }
            Fit.CROP -> {
                val s = maxOf(surfaceW.toFloat() / sw, surfaceH.toFloat() / sh)
                val rw = sw * s
                val rh = sh * s
                listOf(rw, rh, (surfaceW - rw) / 2f, (surfaceH - rh) / 2f)
            }
            Fit.LONG_EDGE -> {
                // Landscape fills horizontally; portrait fills vertically. This keeps the
                // source aspect while letting its long dimension determine the scale.
                val s = if (sw >= sh) surfaceW.toFloat() / sw else surfaceH.toFloat() / sh
                val rw = sw * s
                val rh = sh * s
                listOf(rw, rh, (surfaceW - rw) / 2f, (surfaceH - rh) / 2f)
            }
        }

        val clipScaleX = rectW / surfaceW
        val clipScaleY = rectH / surfaceH
        val clipTransX = (2f * rectX + rectW - surfaceW) / surfaceW
        val clipTransY = (2f * rectY + rectH - surfaceH) / surfaceH

        val tmp = FloatArray(16)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, clipTransX, clipTransY, 0f)
        Matrix.scaleM(tmp, 0, clipScaleX, clipScaleY, 1f)
        val finalMvp = FloatArray(16)
        Matrix.multiplyMM(finalMvp, 0, tmp, 0, mvpMatrix, 0)
        return Transform(u0, v0, u1, v1, finalMvp)
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun createProgram(vertexShader: String, fragmentShader: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragment = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        } catch (error: Throwable) {
            GLES20.glDeleteShader(vertex)
            throw error
        }
        val result = GLES20.glCreateProgram()
        try {
            check(result != 0) { "Unable to create GL program" }
            val linkStatus = IntArray(1)
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "Unable to link GL program: ${GLES20.glGetProgramInfoLog(result)}"
            }
            return result
        } catch (error: Throwable) {
            if (result != 0) GLES20.glDeleteProgram(result)
            throw error
        } finally {
            // Once linked, shader objects are no longer needed by the program.
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create GL shader" }
        try {
            val compileStatus = IntArray(1)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            check(compileStatus[0] == GLES20.GL_TRUE) {
                "Unable to compile GL shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        } catch (error: Throwable) {
            GLES20.glDeleteShader(shader)
            throw error
        }
    }

    companion object {
        private val VERTICES = java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 1f,
                        1f, -1f, 1f, 1f,
                        -1f, 1f, 0f, 0f,
                        1f, 1f, 1f, 0f,
                    ),
                )
                position(0)
            }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvpMatrix;
            uniform mat4 uStMatrix;
            uniform vec4 uTexRect;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vec4 t = vec4(
                    mix(uTexRect.x, uTexRect.z, aTexCoord.x),
                    mix(uTexRect.y, uTexRect.w, aTexCoord.y),
                    0.0,
                    1.0
                );
                vTexCoord = (uStMatrix * t).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
