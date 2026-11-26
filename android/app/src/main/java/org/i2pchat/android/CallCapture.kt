package org.i2pchat.android

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val CAP_TAG = "CallCapture"

/** Camera2 capture that delivers upright I420 frames, at most one per [minIntervalMs]. */
class CallCamera(
    private val context: Context,
    private val onFrame: (I420Frame) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sensorOrientation = 0
    private var front = true
    private var lastFrameMs = 0L

    @Volatile var minIntervalMs: Long = 66L
    @Volatile private var open = false

    val isRunning: Boolean get() = open

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(targetEdge: Int): Boolean {
        if (open) return true
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { mgr.cameraIdList.toList() }.getOrDefault(emptyList())
        val id = ids.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: ids.firstOrNull() ?: return false
        val chars = mgr.getCameraCharacteristics(id)
        front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.isEmpty()) return false
        val fit = sizes.filter { maxOf(it.width, it.height) <= targetEdge }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }!!

        val t = HandlerThread("call-camera").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        val r = ImageReader.newInstance(fit.width, fit.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ ir -> onImage(ir) }, h)
        open = true
        return runCatching {
            mgr.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        startSession(camera, r, h)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        stop()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(CAP_TAG, "camera error $error")
                        camera.close()
                        stop()
                    }
                },
                h,
            )
            true
        }.getOrElse {
            Log.w(CAP_TAG, "openCamera failed", it)
            stop()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice, r: ImageReader, h: Handler) {
        runCatching {
            camera.createCaptureSession(
                listOf(r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        runCatching {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(r.surface)
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()
                            s.setRepeatingRequest(req, null, h)
                        }.onFailure { Log.w(CAP_TAG, "repeating request failed", it) }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.w(CAP_TAG, "camera session configure failed")
                        stop()
                    }
                },
                h,
            )
        }.onFailure {
            Log.w(CAP_TAG, "createCaptureSession failed", it)
            stop()
        }
    }

    private fun onImage(ir: ImageReader) {
        val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < minIntervalMs) return
            lastFrameMs = now
            onFrame(Yuv.imageToI420(img, rotationDegrees()))
        } catch (t: Throwable) {
            Log.d(CAP_TAG, "frame convert failed", t)
        } finally {
            img.close()
        }
    }

    /** Clockwise rotation that makes the sensor image upright for the current screen rotation. */
    private fun rotationDegrees(): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val device = when (dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensorOrientation + device) % 360 else (sensorOrientation - device + 360) % 360
    }

    @Synchronized
    fun stop() {
        open = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
    }
}

/** Hands the foreground service a way to reach the active call without a bound connection. */
object CallRuntime {
    @Volatile var controller: CallController? = null
}

/**
 * Foreground service for an active call: keeps the microphone/camera usable while the app is in
 * the background and hosts the MediaProjection used for screen sharing.
 */
class CallForegroundService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastScreenMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PROJECTION_STOP -> {
                stopProjection()
                enterForeground(withProjection = false)
            }
            ACTION_PROJECTION_START -> {
                enterForeground(withProjection = true)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data != null) startProjection(code, data) else CallRuntime.controller?.onScreenCaptureEnded()
            }
            else -> enterForeground(withProjection = projection != null)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(withProjection: Boolean) {
        val notification = NotificationHelper.callNotification(this, getString(R.string.call_notification))
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                var types = 0
                if (granted(android.Manifest.permission.RECORD_AUDIO)) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (granted(android.Manifest.permission.CAMERA) && CallRuntime.controller?.state?.value?.cam == true) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (withProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NotificationHelper.CALL_ID, notification, types)
            } else {
                startForeground(NotificationHelper.CALL_ID, notification)
            }
        }.onFailure { Log.w(CAP_TAG, "startForeground failed", it) }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("WrongConstant")
    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjection()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (p == null) {
            CallRuntime.controller?.onScreenCaptureEnded()
            return
        }
        projection = p
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val edge = CallRuntime.controller?.state?.value?.videoMaxEdge?.coerceAtMost(SCREEN_MAX_EDGE) ?: 480
        val scale = edge.toDouble() / maxOf(metrics.widthPixels, metrics.heightPixels)
        val w = ((metrics.widthPixels * scale).toInt() and 1.inv()).coerceAtLeast(64)
        val h = ((metrics.heightPixels * scale).toInt() and 1.inv()).coerceAtLeast(64)
        val t = HandlerThread("call-screen").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img: Image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastScreenMs >= SCREEN_MIN_INTERVAL_MS) {
                    lastScreenMs = now
                    CallRuntime.controller?.onScreenFrame(Yuv.rgbaImageToI420(img))
                }
            } catch (t2: Throwable) {
                Log.d(CAP_TAG, "screen frame failed", t2)
            } finally {
                img.close()
            }
        }, handler)
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    CallRuntime.controller?.onScreenCaptureEnded()
                    stopProjection()
                }
            },
            handler,
        )
        display = runCatching {
            p.createVirtualDisplay(
                "i2pchat-call-screen", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler,
            )
        }.onFailure {
            Log.w(CAP_TAG, "createVirtualDisplay failed", it)
            CallRuntime.controller?.onScreenCaptureEnded()
        }.getOrNull()
    }

    private fun stopProjection() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { thread?.quitSafely() }
        display = null
        reader = null
        projection = null
        thread = null
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.i2pchat.android.action.STOP_CALL"
        const val ACTION_PROJECTION_START = "org.i2pchat.android.action.PROJECTION_START"
        const val ACTION_PROJECTION_STOP = "org.i2pchat.android.action.PROJECTION_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val SCREEN_MAX_EDGE = 640
        private const val SCREEN_MIN_INTERVAL_MS = 66L

        /** Start (or refresh) the call foreground service; never throws. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, CallForegroundService::class.java)
            runCatching { app.startForegroundService(intent) }
                .onFailure { Log.w(CAP_TAG, "call service start failed", it) }
        }

        fun startProjection(context: Context, resultCode: Int, data: Intent) {
            val app = context.applicationContext
            val intent = Intent(app, CallForegroundService::class.java)
                .setAction(ACTION_PROJECTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            runCatching { app.startForegroundService(intent) }
                .onFailure {
                    Log.w(CAP_TAG, "projection service start failed", it)
                    CallRuntime.controller?.onScreenCaptureEnded()
                }
        }

        fun stopProjection(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(Intent(app, CallForegroundService::class.java).setAction(ACTION_PROJECTION_STOP))
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(Intent(app, CallForegroundService::class.java).setAction(ACTION_STOP))
            }
            app.stopService(Intent(app, CallForegroundService::class.java))
        }
    }
}
