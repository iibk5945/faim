package com.yyh.gametools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        private const val TAG = "ffaim"
        private const val NOTIF_ID = 42
        private const val MIN_FRAME_MS = 66L
        private const val DOT = 40

        fun intent(ctx: Context, code: Int, data: Intent) =
            Intent(ctx, OverlayService::class.java)
                .putExtra("code", code)
                .putExtra("data", data)
    }

    private lateinit var wm: WindowManager
    private var hud: TextView? = null
    private var dot: View? = null
    private var center: View? = null
    private var toggle: TextView? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val busy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var fps = 0
    private var frames = 0
    private var secondStart = 0L
    private var srcW = 0
    private var srcH = 0
    private var screenW = 0
    private var screenH = 0
    private val aimCenter = PointF()

    @Volatile
    private var aimOn = false

    private val aim = Aim(1.0f)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotif()
        wm = getSystemService(WindowManager::class.java)

        val dm = DisplayMetrics()
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            .getRealMetrics(dm)
        screenW = maxOf(dm.widthPixels, dm.heightPixels)
        screenH = minOf(dm.widthPixels, dm.heightPixels)
        aimCenter.set(screenW / 2f, screenH / 2f)

        buildOverlay()

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")

        if (data != null) startCapture(code, data)
        return START_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ffaim", "ffaim", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, "ffaim")
            .setContentTitle("Game Tools running")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun params(w: Int, h: Int, touchable: Boolean) =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildOverlay() {
        hud = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 11f
            text = "booting"
        }
        wm.addView(
            hud,
            params(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                false
            ).apply { x = 40; y = 140 }
        )

        toggle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.argb(170, 0, 0, 0))
            }
            text = "AIM OFF"
            setOnClickListener {
                aimOn = !aimOn
                aim.reset()
                text = if (aimOn) "AIM ON" else "AIM OFF"
            }
        }
        wm.addView(
            toggle,
            params(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                true
            ).apply { x = 40; y = 60 }
        )

        center = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(2, Color.argb(160, 255, 255, 255))
            }
        }
        wm.addView(center, params(DOT, DOT, false).apply {
            x = screenW / 2 - DOT / 2
            y = screenH / 2 - DOT / 2
        })

        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 0, 255, 0))
                setStroke(3, Color.BLACK)
            }
            visibility = View.INVISIBLE
        }
        wm.addView(dot, params(DOT, DOT, false))
    }

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run {
            Log.e(TAG, "projection denied")
            return
        }
        projection = proj
        srcW = screenW
        srcH = screenH

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))

        reader = ImageReader.newInstance(srcW, srcH, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ onFrame(it) }, Handler(Looper.getMainLooper()))
        }

        display = proj.createVirtualDisplay(
            "ffaim", srcW, srcH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
    }

    private fun onFrame(r: ImageReader) {
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < MIN_FRAME_MS) {
            r.acquireLatestImage()?.close()
            return
        }
        if (!busy.compareAndSet(false, true)) return
        lastFrameAt = now

        try {
            val img = r.acquireLatestImage() ?: return
            try {
                val plane = img.planes[0]
                val rowPx = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)

                frames++
                if (now - secondStart >= 1000) {
                    fps = frames
                    frames = 0
                    secondStart = now
                }

                val t = Detector.detect(bmp, srcW, srcH)
                hud?.text = "${fps}fps ${bmp.width}x${bmp.height}\n" +
                    (t?.let { "T ${it.x.toInt()},${it.y.toInt()} h${it.hits}" } ?: "no target")

                placeDot(t)

                if (aimOn) {
                    val mv = aim.update(aimCenter, t, now)
                    if (mv != null) {
                        SwipeService.instance?.swipe(
                            aimCenter.x, aimCenter.y, mv.dx, mv.dy
                        )
                    }
                }

                bmp.recycle()
            } finally {
                img.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "frame", e)
        } finally {
            busy.set(false)
        }
    }

    private fun placeDot(t: Detector.Target?) {
        val v = dot ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        if (t == null) {
            v.visibility = View.INVISIBLE
        } else {
            v.visibility = View.VISIBLE
            p.x = (t.x - DOT / 2f).toInt()
            p.y = (t.y - DOT / 2f).toInt()
        }
        try { wm.updateViewLayout(v, p) } catch (_: Throwable) {}
    }

    private fun stopCapture() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
    }

    override fun onDestroy() {
        stopCapture()
        listOfNotNull(hud, toggle, center, dot).forEach {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }
}
