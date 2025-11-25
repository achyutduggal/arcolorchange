package com.example.arcolorchange

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.arcolorchange/segmentation"
    private var segmentationEngine: FastSAMSegmentation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "initializeModel" -> {
                    scope.launch {
                        try {
                            segmentationEngine = FastSAMSegmentation(this@MainActivity)
                            val success = segmentationEngine!!.initialize()
                            mainHandler.post { result.success(success) }
                        } catch (e: Exception) {
                            mainHandler.post { result.error("INIT_ERROR", e.message, null) }
                        }
                    }
                }

                "segmentAtPoint" -> {
                    val imageBytes = call.argument<ByteArray>("imageBytes")
                    val imageWidth = call.argument<Int>("imageWidth")
                    val imageHeight = call.argument<Int>("imageHeight")
                    val tapX = call.argument<Double>("tapX")
                    val tapY = call.argument<Double>("tapY")

                    if (imageBytes == null || imageWidth == null || imageHeight == null ||
                        tapX == null || tapY == null
                    ) {
                        result.error("INVALID_ARGS", "Missing required arguments", null)
                        return@setMethodCallHandler
                    }

                    scope.launch {
                        try {
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            val mask = segmentationEngine?.segmentAtPoint(
                                bitmap,
                                tapX.toFloat(),
                                tapY.toFloat()
                            )
                            bitmap.recycle()
                            mainHandler.post { result.success(mask) }
                        } catch (e: Exception) {
                            mainHandler.post { result.error("SEGMENT_ERROR", e.message, null) }
                        }
                    }
                }

                "getAllMasks" -> {
                    val imageBytes = call.argument<ByteArray>("imageBytes")
                    val imageWidth = call.argument<Int>("imageWidth")
                    val imageHeight = call.argument<Int>("imageHeight")

                    if (imageBytes == null || imageWidth == null || imageHeight == null) {
                        result.error("INVALID_ARGS", "Missing required arguments", null)
                        return@setMethodCallHandler
                    }

                    scope.launch {
                        try {
                            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            val masks = segmentationEngine?.getAllMasks(bitmap)
                            bitmap.recycle()
                            mainHandler.post { result.success(masks) }
                        } catch (e: Exception) {
                            mainHandler.post { result.error("SEGMENT_ERROR", e.message, null) }
                        }
                    }
                }

                "disposeModel" -> {
                    scope.launch {
                        segmentationEngine?.release()
                        segmentationEngine = null
                        mainHandler.post { result.success(null) }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        segmentationEngine?.release()
        super.onDestroy()
    }
}
