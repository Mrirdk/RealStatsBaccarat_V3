package com.stats.baccaratoverlay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

class AutoRobotOverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var robotView: View
    private lateinit var roadScannerView: View
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var roadScannerParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val handler = Handler(Looper.getMainLooper())

    private val history = mutableListOf<String>()
    private var lastPredictedSide: String = "WAIT"
    private var toolWins = 0
    private var toolLosses = 0
    private var lastSourceNote = "Tự động"

    private var isAutoScanning = true
    private var isRoadScannerVisible = false
    private var isOcrBusy = false

    // Chống đếm trùng & xử lý thông minh cho ván Hòa (Tie)
    private var cooldownUntilMs = 0L
    private var bannerClearFrames = 2
    private var candidateSide: String? = null
    private var candidateStreak = 0

    // Lưu khung hình nghỉ làm nền so sánh biến động banner (tránh nhận nhầm nền bàn màu xanh lá)
    private var baselineR: IntArray? = null
    private var baselineG: IntArray? = null
    private var baselineB: IntArray? = null

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning) {
                captureAndAnalyzeFullScreen()
            }
            handler.postDelayed(this, 750L) // Quét toàn màn hình mỗi 0.75 giây
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAndroid14Foreground()
        setupOverlayViews()
        initMediaProjection(resultCode, resultData)

        handler.removeCallbacks(scanLoopRunnable)
        handler.postDelayed(scanLoopRunnable, 900L)

        return START_NOT_STICKY
    }

    private fun startAndroid14Foreground() {
        val channelId = "autorobot_v3_channel"
        val channel = NotificationChannel(
            channelId,
            "AutoRobot Baccarat V3 Vision",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("🤖 AutoRobot Baccarat V3 đang soi bàn")
            .setContentText("Tự động nhận Con / Cái / Hòa & bắt cầu toàn màn hình")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                3003,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(3003, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "AutoRobotV3FullVision",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupOverlayViews() {
        if (::windowManager.isInitialized) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 1. Khởi tạo Con Robot Tách Nền + Dòng chữ báo Con/Cái kế bên
        robotView = LayoutInflater.from(this).inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 18
            y = 110
        }
        windowManager.addView(robotView, robotParams)

        // 2. Khởi tạo Khung Quét Bảng Cầu (ẩn mặc định, bật khi bấm nút Quét Bảng Cầu)
        roadScannerView = LayoutInflater.from(this).inflate(R.layout.road_scanner_layout, null)
        roadScannerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - 540).coerceAtLeast(20) / 2
            y = (resources.displayMetrics.heightPixels * 0.68).toInt()
        }
        roadScannerView.visibility = View.GONE
        windowManager.addView(roadScannerView, roadScannerParams)

        attachDragAndTapToRobot()
        attachDragToRoadScanner()
        bindButtons()
        refreshHudDisplay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTapToRobot() {
        val robotImg = robotView.findViewById<ImageView>(R.id.ivRobotCutout)
        val drawer = robotView.findViewById<LinearLayout>(R.id.detailDrawerPanel)
        val toggleArrow = robotView.findViewById<TextView>(R.id.btnToggleDrawer)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        robotImg.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = robotParams.x
                    initY = robotParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                        robotParams.x = initX + dx
                        robotParams.y = initY + dy
                        windowManager.updateViewLayout(robotView, robotParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Bấm nhẹ vào Robot để mở/thu ngăn điều khiển chi tiết
                        if (drawer.visibility == View.VISIBLE) {
                            drawer.visibility = View.GONE
                            toggleArrow.text = "▼"
                        } else {
                            drawer.visibility = View.VISIBLE
                            toggleArrow.text = "▲"
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragToRoadScanner() {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        roadScannerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = roadScannerParams.x
                    initY = roadScannerParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    roadScannerParams.x = initX + (event.rawX - touchX).toInt()
                    roadScannerParams.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(roadScannerView, roadScannerParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun bindButtons() {
        val drawer = robotView.findViewById<LinearLayout>(R.id.detailDrawerPanel)
        val toggleArrow = robotView.findViewById<TextView>(R.id.btnToggleDrawer)
        val btnCloseService = robotView.findViewById<TextView>(R.id.btnCloseService)

        val btnOpenRoadScanner = robotView.findViewById<Button>(R.id.btnOpenRoadScanner)
        val btnTogglePause = robotView.findViewById<Button>(R.id.btnTogglePause)
        val btnResetTable = robotView.findViewById<Button>(R.id.btnResetTable)

        val btnAddP = robotView.findViewById<Button>(R.id.btnAddP)
        val btnAddB = robotView.findViewById<Button>(R.id.btnAddB)
        val btnAddT = robotView.findViewById<Button>(R.id.btnAddT)
        val btnUndoLast = robotView.findViewById<Button>(R.id.btnUndoLast)

        val btnCaptureRoadNow = roadScannerView.findViewById<Button>(R.id.btnCaptureRoadNow)
        val btnCloseRoadBox = roadScannerView.findViewById<Button>(R.id.btnCloseRoadBox)

        toggleArrow.setOnClickListener {
            if (drawer.visibility == View.VISIBLE) {
                drawer.visibility = View.GONE
                toggleArrow.text = "▼"
            } else {
                drawer.visibility = View.VISIBLE
                toggleArrow.text = "▲"
            }
        }

        btnCloseService.setOnClickListener { stopSelf() }

        btnTogglePause.setOnClickListener {
            isAutoScanning = !isAutoScanning
            btnTogglePause.text = if (isAutoScanning) "⏸ Tạm dừng" else "▶️ Tiếp tục"
        }

        btnResetTable.setOnClickListener {
            history.clear()
            toolWins = 0
            toolLosses = 0
            lastPredictedSide = "WAIT"
            cooldownUntilMs = 0L
            bannerClearFrames = 2
            candidateSide = null
            candidateStreak = 0
            baselineR = null
            lastSourceNote = "Làm mới bàn"
            refreshHudDisplay()
        }

        btnOpenRoadScanner.setOnClickListener {
            isRoadScannerVisible = !isRoadScannerVisible
            roadScannerView.visibility = if (isRoadScannerVisible) View.VISIBLE else View.GONE
        }

        btnCloseRoadBox.setOnClickListener {
            isRoadScannerVisible = false
            roadScannerView.visibility = View.GONE
        }

        btnCaptureRoadNow.setOnClickListener {
            scanBeadPlateGridNow()
        }

        btnAddP.setOnClickListener { recordDetectedOutcome("P", "Thêm tay") }
        btnAddB.setOnClickListener { recordDetectedOutcome("B", "Thêm tay") }
        btnAddT.setOnClickListener { recordDetectedOutcome("T", "Thêm tay") }
        btnUndoLast.setOnClickListener {
            if (history.isNotEmpty()) {
                history.removeAt(history.lastIndex)
                lastSourceNote = "Xóa 1 tay"
                refreshHudDisplay()
            }
        }
    }

    private fun acquireScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val rawBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)
            if (rawBitmap.width != image.width) {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
                rawBitmap.recycle()
                cropped
            } else {
                rawBitmap
            }
        } finally {
            image.close()
        }
    }

    private fun captureAndAnalyzeFullScreen() {
        val bitmap = acquireScreenBitmap() ?: return

        // Tọa độ HUD của Robot để loại trừ, tránh Robot tự đọc chữ của chính mình
        val hudLoc = IntArray(2)
        robotView.getLocationOnScreen(hudLoc)
        val hudLeft = hudLoc[0] - 8
        val hudTop = hudLoc[1] - 8
        val hudRight = hudLoc[0] + robotView.width + 8
        val hudBottom = hudLoc[1] + robotView.height + 8

        // Vùng quét trung tâm & phía trên (12% -> 68% chiều cao màn hình) nơi xuất hiện bảng kết quả
        val scanTop = (bitmap.height * 0.12).toInt()
        val scanBottom = (bitmap.height * 0.68).toInt()
        val scanLeft = (bitmap.width * 0.08).toInt()
        val scanRight = (bitmap.width * 0.92).toInt()

        // 1. Phân tích màu sắc & biến động khung hình (Delta HSV) để bắt nhanh cả P, B và HÒA (T)
        val colorCandidate = detectColorDeltaBanner(
            bitmap, scanLeft, scanTop, scanRight, scanBottom,
            hudLeft, hudTop, hudRight, hudBottom
        )

        // 2. Chạy song song OCR nhận diện chữ trên màn hình ("PLAYER WIN", "BANKER WIN", "TIE", "HÒA", "CON THẮNG", "CÁI THẮNG")
        if (!isOcrBusy) {
            isOcrBusy = true
            val cropH = (scanBottom - scanTop).coerceAtLeast(32)
            val cropW = (scanRight - scanLeft).coerceAtLeast(32)
            val ocrBitmap = Bitmap.createBitmap(bitmap, scanLeft, scanTop, cropW, cropH)

            // Che vùng HUD của Robot trên ảnh OCR bằng màu đen
            val relHudL = (hudLeft - scanLeft).coerceIn(0, cropW)
            val relHudT = (hudTop - scanTop).coerceIn(0, cropH)
            val relHudR = (hudRight - scanLeft).coerceIn(0, cropW)
            val relHudB = (hudBottom - scanTop).coerceIn(0, cropH)
            if (relHudR > relHudL && relHudB > relHudT) {
                for (y in relHudT until relHudB) {
                    for (x in relHudL until relHudR) {
                        ocrBitmap.setPixel(x, y, Color.BLACK)
                    }
                }
            }

            val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val ocrSide = parseBaccaratOcrText(visionText.text)
                    handleDetectionFrame(ocrSide ?: colorCandidate, if (ocrSide != null) "OCR Toàn Màn" else "Quét Màu Toàn Màn")
                }
                .addOnFailureListener {
                    handleDetectionFrame(colorCandidate, "Quét Màu Toàn Màn")
                }
                .addOnCompleteListener {
                    ocrBitmap.recycle()
                    isOcrBusy = false
                }
        } else {
            handleDetectionFrame(colorCandidate, "Quét Màu Toàn Màn")
        }

        bitmap.recycle()
    }

    private fun parseBaccaratOcrText(rawText: String): String? {
        if (rawText.isBlank()) return null
        val normalized = Normalizer.normalize(rawText, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase(Locale.US)

        // Ưu tiên kiểm tra HÒA (TIE) trước để không bao giờ bị khựng ở ván Hòa
        val tieKeywords = listOf("TIE WIN", "TIE WINS", "HOA THANG", "KET QUA HOA", "DRAW WIN")
        if (tieKeywords.any { normalized.contains(it) }) return "T"

        val lines = normalized.lines().map { it.trim() }
        for (line in lines) {
            if (line == "TIE" || line == "HOA" || line == "HÒA" || line == "DRAW") {
                return "T"
            }
            if (line.contains("PLAYER WIN") || line.contains("CON THANG") || line.contains("TAY CON THANG")) {
                return "P"
            }
            if (line.contains("BANKER WIN") || line.contains("CAI THANG") || line.contains("NHA CAI THANG")) {
                return "B"
            }
        }
        return null
    }

    private fun detectColorDeltaBanner(
        bitmap: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int,
        hudL: Int, hudT: Int, hudR: Int, hudB: Int
    ): String? {
        val step = 6
        val cols = ((right - left) / step).coerceAtLeast(1)
        val rows = ((bottom - top) / step).coerceAtLeast(1)
        val totalSamples = cols * rows

        val curR = IntArray(totalSamples)
        val curG = IntArray(totalSamples)
        val curB = IntArray(totalSamples)

        val prevR = baselineR
        val prevG = baselineG
        val prevB = baselineB

        var newBlue = 0
        var newRed = 0
        var newGreen = 0
        var validPixels = 0
        val hsv = FloatArray(3)

        var idx = 0
        for (rIdx in 0 until rows) {
            val y = top + rIdx * step
            for (cIdx in 0 until cols) {
                val x = left + cIdx * step
                if (idx >= totalSamples) break

                // Bỏ qua vùng tọa độ của chính con Robot
                if (x in hudL..hudR && y in hudT..hudB) {
                    idx++
                    continue
                }

                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                curR[idx] = r
                curG[idx] = g
                curB[idx] = b
                validPixels++

                // Chỉ tính các điểm ảnh mới bật sáng / đổi màu so với nền bàn tĩnh
                val changedFromBaseline = if (prevR != null && prevG != null && prevB != null && idx < prevR.size) {
                    val diff = abs(r - prevR[idx]) + abs(g - prevG[idx]) + abs(b - prevB[idx])
                    diff > 48
                } else {
                    false
                }

                if (changedFromBaseline) {
                    Color.colorToHSV(px, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val valBright = hsv[2]

                    if (sat >= 0.30f && valBright >= 0.30f) {
                        when {
                            hue in 195f..248f -> newBlue++
                            hue <= 16f || hue >= 338f -> newRed++
                            hue in 72f..168f -> newGreen++ // Dải xanh lá rộng bắt trọn banner Hòa/Tie
                        }
                    }
                }
                idx++
            }
        }

        // Cập nhật nền tĩnh từ từ để thích nghi ánh sáng bàn
        if (prevR == null || prevR.size != totalSamples) {
            baselineR = curR
            baselineG = curG
            baselineB = curB
            return null
        } else {
            for (i in 0 until totalSamples) {
                prevR[i] = (prevR[i] * 3 + curR[i]) / 4
                prevG!![i] = (prevG[i] * 3 + curG[i]) / 4
                prevB!![i] = (prevB[i] * 3 + curB[i]) / 4
            }
        }

        if (validPixels < 50) return null
        val minSpike = (validPixels * 0.045).toInt().coerceAtLeast(14)

        return when {
            newGreen >= minSpike && newGreen > newRed * 1.35 && newGreen > newBlue * 1.35 -> "T"
            newRed >= minSpike && newRed > newBlue * 1.4 && newRed > newGreen * 1.3 -> "B"
            newBlue >= minSpike && newBlue > newRed * 1.4 && newBlue > newGreen * 1.3 -> "P"
            else -> null
        }
    }

    private fun handleDetectionFrame(detectedSide: String?, sourceLabel: String) {
        val now = System.currentTimeMillis()

        if (detectedSide == null) {
            bannerClearFrames++
            candidateSide = null
            candidateStreak = 0
            return
        }

        // Chỉ nhận ván mới khi đã hết thời gian khóa tối thiểu VÀ bảng thắng cũ đã tắt ít nhất 1 nhịp
        if (now < cooldownUntilMs && bannerClearFrames < 2) {
            return
        }

        if (detectedSide == candidateSide) {
            candidateStreak++
        } else {
            candidateSide = detectedSide
            candidateStreak = 1
        }

        // Xác nhận ngay sau 2 nhịp khớp (hoặc 1 nhịp nếu OCR đọc rõ chữ)
        val requiredStreak = if (sourceLabel.startsWith("OCR")) 1 else 2
        if (candidateStreak >= requiredStreak) {
            candidateSide = null
            candidateStreak = 0
            bannerClearFrames = 0
            // Khóa 6.5 giây cho Hòa (ván Hòa chia lại nhanh) và 9.5 giây cho Con/Cái
            cooldownUntilMs = now + if (detectedSide == "T") 6_500L else 9_500L

            recordDetectedOutcome(detectedSide, sourceLabel)
        }
    }

    private fun recordDetectedOutcome(outcome: String, sourceNote: String) {
        // Đối chiếu kết quả thật với lệnh chốt trước đó của Robot để tính tỷ lệ ăn/thua thật
        if ((outcome == "P" || outcome == "B") && (lastPredictedSide == "P" || lastPredictedSide == "B")) {
            if (outcome == lastPredictedSide) {
                toolWins++
            } else {
                toolLosses++
            }
        }

        history.add(outcome)
        lastSourceNote = sourceNote
        vibrateFeedback()
        refreshHudDisplay()
    }

    private fun scanBeadPlateGridNow() {
        val bitmap = acquireScreenBitmap()
        if (bitmap == null) {
            Toast.makeText(this, "Chưa lấy được khung hình, thử lại!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gridFrame = roadScannerView.findViewById<FrameLayout>(R.id.roadGridFrame)
            val loc = IntArray(2)
            gridFrame.getLocationOnScreen(loc)

            val left = loc[0].coerceIn(0, bitmap.width - 10)
            val top = loc[1].coerceIn(0, bitmap.height - 10)
            val width = gridFrame.width.coerceIn(20, bitmap.width - left)
            val height = gridFrame.height.coerceIn(20, bitmap.height - top)

            // Bảng cầu chuẩn Baccarat gồm 6 hàng x 14 cột
            val rows = 6
            val cols = 14
            val cellW = width.toFloat() / cols
            val cellH = height.toFloat() / rows

            val extracted = mutableListOf<String>()
            val hsv = FloatArray(3)

            // Đọc theo chuẩn Bead Plate: từ trên xuống dưới từng cột, từ trái qua phải
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    val cx = (left + (c + 0.5f) * cellW).toInt()
                    val cy = (top + (r + 0.5f) * cellH).toInt()
                    val radiusX = (cellW * 0.28f).toInt().coerceAtLeast(2)
                    val radiusY = (cellH * 0.28f).toInt().coerceAtLeast(2)

                    var bVotes = 0
                    var rVotes = 0
                    var gVotes = 0

                    for (dy in -radiusY..radiusY step 2) {
                        for (dx in -radiusX..radiusX step 2) {
                            val pxX = (cx + dx).coerceIn(0, bitmap.width - 1)
                            val pxY = (cy + dy).coerceIn(0, bitmap.height - 1)
                            Color.colorToHSV(bitmap.getPixel(pxX, pxY), hsv)
                            val h = hsv[0]
                            val s = hsv[1]
                            val v = hsv[2]

                            if (s >= 0.42f && v >= 0.35f) {
                                when {
                                    h in 195f..248f -> bVotes++
                                    h <= 16f || h >= 338f -> rVotes++
                                    h in 75f..165f -> gVotes++
                                }
                            }
                        }
                    }

                    val cellOutcome = when {
                        rVotes >= 3 && rVotes > bVotes && rVotes > gVotes -> "B"
                        bVotes >= 3 && bVotes > rVotes && bVotes > gVotes -> "P"
                        gVotes >= 3 && gVotes > rVotes && gVotes > bVotes -> "T"
                        else -> null
                    }
                    if (cellOutcome != null) {
                        extracted.add(cellOutcome)
                    }
                }
            }

            if (extracted.isNotEmpty()) {
                history.clear()
                history.addAll(extracted)
                toolWins = 0
                toolLosses = 0
                lastSourceNote = "Quét Bảng (${extracted.size} tay)"
                vibrateFeedback()
                refreshHudDisplay()
                Toast.makeText(
                    this,
                    "🤖 Đã bắt xong ${extracted.size} ván từ bảng cầu!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Chưa thấy chấm cầu xanh/đỏ trong khung, hãy kéo khung đè đúng bảng cầu!",
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun refreshHudDisplay() {
        val report = BaccaratBridgeEngineV3.analyze(history, toolWins, toolLosses, lastSourceNote)
        lastPredictedSide = report.recommendedSide

        val tvSideMain = robotView.findViewById<TextView>(R.id.tvSideCalloutMain)
        val tvSideSub = robotView.findViewById<TextView>(R.id.tvSideCalloutSub)
        val tvSideBridge = robotView.findViewById<TextView>(R.id.tvSideBridgeTag)

        val tvLastEvent = robotView.findViewById<TextView>(R.id.tvLastDetectedEvent)
        val tvDetail = robotView.findViewById<TextView>(R.id.tvBridgeAnalysisDetail)
        val tvWinLoss = robotView.findViewById<TextView>(R.id.tvToolWinLossStats)
        val tvTape = robotView.findViewById<TextView>(R.id.tvHistoryTape)

        tvSideMain.text = report.sideMainText
        tvSideSub.text = report.sideSubText
        tvSideBridge.text = report.sideBridgeTag

        tvLastEvent.text = report.lastEventText
        tvDetail.text = report.bridgeDetailText
        tvWinLoss.text = report.winLossText
        tvTape.text = report.historyTapeText

        when (report.recommendedSide) {
            "P" -> tvSideMain.setTextColor(Color.parseColor("#40C4FF"))
            "B" -> tvSideMain.setTextColor(Color.parseColor("#FF5252"))
            else -> tvSideMain.setTextColor(Color.parseColor("#69F0AE"))
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(70)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanLoopRunnable)
        textRecognizer.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::robotView.isInitialized) windowManager.removeView(robotView)
        if (::roadScannerView.isInitialized) windowManager.removeView(roadScannerView)
    }
}
