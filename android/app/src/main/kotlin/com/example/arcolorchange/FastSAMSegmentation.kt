package com.example.arcolorchange

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class FastSAMSegmentation(private val context: Context) {
    companion object {
        private const val TAG = "FastSAMSegmentation"
        private const val MODEL_INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.1f  // Lowered for debugging
        private const val IOU_THRESHOLD = 0.7f
        private const val MASK_THRESHOLD = 0.3f  // Lowered for debugging
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "=== INITIALIZING MODEL ===")
            ortEnvironment = OrtEnvironment.getEnvironment()
            Log.d(TAG, "ORT Environment created")

            val modelDir = copyModelFromAssets()
            if (modelDir != null) {
                val modelFile = File(modelDir, "model.onnx")
                Log.d(TAG, "Model file path: ${modelFile.absolutePath}")
                Log.d(TAG, "Model file exists: ${modelFile.exists()}, size: ${modelFile.length()}")

                val dataFile = File(modelDir, "model.data")
                Log.d(TAG, "Data file exists: ${dataFile.exists()}, size: ${dataFile.length()}")

                if (modelFile.exists()) {
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                        setIntraOpNumThreads(4)
                    }

                    Log.d(TAG, "Creating ORT session...")
                    ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)
                    Log.d(TAG, "ORT session created successfully")

                    // Log model info
                    Log.d(TAG, "=== MODEL INFO ===")
                    Log.d(TAG, "Input count: ${ortSession!!.inputNames.size}")
                    Log.d(TAG, "Output count: ${ortSession!!.outputNames.size}")

                    for (inputName in ortSession!!.inputNames) {
                        val inputInfo = ortSession!!.inputInfo[inputName]
                        Log.d(TAG, "Input '$inputName': $inputInfo")
                        if (inputInfo != null) {
                            val tensorInfo = inputInfo.info as? TensorInfo
                            Log.d(TAG, "  Shape: ${tensorInfo?.shape?.contentToString()}")
                            Log.d(TAG, "  Type: ${tensorInfo?.type}")
                        }
                    }

                    for (outputName in ortSession!!.outputNames) {
                        val outputInfo = ortSession!!.outputInfo[outputName]
                        Log.d(TAG, "Output '$outputName': $outputInfo")
                        if (outputInfo != null) {
                            val tensorInfo = outputInfo.info as? TensorInfo
                            Log.d(TAG, "  Shape: ${tensorInfo?.shape?.contentToString()}")
                            Log.d(TAG, "  Type: ${tensorInfo?.type}")
                        }
                    }

                    isInitialized = true
                    Log.d(TAG, "=== MODEL INITIALIZED SUCCESSFULLY ===")
                    true
                } else {
                    Log.e(TAG, "Model file not found, using demo mode")
                    isInitialized = true
                    true
                }
            } else {
                Log.e(TAG, "Failed to copy model files, using demo mode")
                isInitialized = true
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model: ${e.message}")
            e.printStackTrace()
            isInitialized = true
            true
        }
    }

    private fun copyModelFromAssets(): File? {
        return try {
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
                Log.d(TAG, "Created model directory: ${modelDir.absolutePath}")
            }

            val assetFiles = context.assets.list("models") ?: emptyArray()
            Log.d(TAG, "Assets in models folder: ${assetFiles.joinToString(", ")}")

            for (fileName in assetFiles) {
                if (fileName.endsWith(".onnx") || fileName.endsWith(".data")) {
                    val outputFile = File(modelDir, fileName)
                    if (!outputFile.exists() || outputFile.length() == 0L) {
                        Log.d(TAG, "Copying $fileName...")
                        context.assets.open("models/$fileName").use { input ->
                            outputFile.outputStream().use { output ->
                                val bytes = input.copyTo(output)
                                Log.d(TAG, "Copied $fileName: $bytes bytes")
                            }
                        }
                    } else {
                        Log.d(TAG, "$fileName already exists: ${outputFile.length()} bytes")
                    }
                }
            }

            modelDir
        } catch (e: Exception) {
            Log.e(TAG, "Error copying model files: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun segmentAtPoint(bitmap: Bitmap, normalizedX: Float, normalizedY: Float): ByteArray {
        Log.d(TAG, "=== SEGMENT AT POINT ===")
        Log.d(TAG, "Tap: ($normalizedX, $normalizedY)")
        Log.d(TAG, "Bitmap: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "Model initialized: $isInitialized, Session: ${ortSession != null}")

        if (!isInitialized) {
            Log.e(TAG, "Model not initialized!")
            throw IllegalStateException("Model not initialized")
        }

        if (ortSession == null) {
            Log.d(TAG, "No model session, returning demo mask")
            return generateDemoMask(normalizedX, normalizedY)
        }

        return try {
            // Preprocess
            Log.d(TAG, "Preprocessing image...")
            val inputTensor = preprocessImage(bitmap)
            Log.d(TAG, "Input tensor created")

            // Run inference
            Log.d(TAG, "Running inference...")
            val startTime = System.currentTimeMillis()
            val outputs = runInference(inputTensor)
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference completed in ${inferenceTime}ms")
            Log.d(TAG, "Output count: ${outputs.size()}")

            // Log ALL outputs in detail
            logOutputs(outputs)

            // Post-process
            Log.d(TAG, "Post-processing...")
            val allMasks = postProcessOutput(outputs)
            Log.d(TAG, "Generated ${allMasks.size} masks")

            inputTensor.close()
            outputs.close()

            if (allMasks.isEmpty()) {
                Log.d(TAG, "No masks generated, using demo mask")
                return generateDemoMask(normalizedX, normalizedY)
            }

            // Select best mask
            val selectedMask = selectMaskAtPoint(allMasks, normalizedX, normalizedY)
            val nonZero = selectedMask.count { (it.toInt() and 0xFF) > 127 }
            Log.d(TAG, "Selected mask: ${selectedMask.size} bytes, $nonZero non-zero pixels")

            selectedMask
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation error: ${e.message}")
            e.printStackTrace()
            generateDemoMask(normalizedX, normalizedY)
        }
    }

    private fun logOutputs(outputs: OrtSession.Result) {
        Log.d(TAG, "=== OUTPUT DETAILS ===")

        for (i in 0 until outputs.size().toInt()) {
            try {
                val outputName = ortSession!!.outputNames.toList()[i]
                val output = outputs.get(i)
                Log.d(TAG, "Output[$i] name: $outputName, type: ${output?.javaClass?.simpleName}")

                if (output is OnnxTensor) {
                    val shape = output.info.shape
                    Log.d(TAG, "  Shape: ${shape.contentToString()}")
                    Log.d(TAG, "  Type: ${output.info.type}")

                    // Get buffer and log sample values
                    val buffer = output.floatBuffer
                    val totalElements = shape.fold(1L) { acc, l -> acc * l }.toInt()
                    Log.d(TAG, "  Total elements: $totalElements")

                    // Log first 20 values
                    val sampleSize = minOf(20, totalElements)
                    val samples = FloatArray(sampleSize)
                    buffer.rewind()
                    for (j in 0 until sampleSize) {
                        samples[j] = buffer.get(j)
                    }
                    Log.d(TAG, "  First $sampleSize values: ${samples.contentToString()}")

                    // Log min/max
                    buffer.rewind()
                    var minVal = Float.MAX_VALUE
                    var maxVal = Float.MIN_VALUE
                    for (j in 0 until totalElements) {
                        val v = buffer.get(j)
                        if (v < minVal) minVal = v
                        if (v > maxVal) maxVal = v
                    }
                    Log.d(TAG, "  Min: $minVal, Max: $maxVal")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error logging output[$i]: ${e.message}")
            }
        }
    }

    fun getAllMasks(bitmap: Bitmap): List<ByteArray> {
        if (!isInitialized) throw IllegalStateException("Model not initialized")
        if (ortSession == null) return listOf(generateDemoMask(0.5f, 0.5f))

        return try {
            val inputTensor = preprocessImage(bitmap)
            val outputs = runInference(inputTensor)
            val masks = postProcessOutput(outputs)
            inputTensor.close()
            outputs.close()
            if (masks.isEmpty()) listOf(generateDemoMask(0.5f, 0.5f)) else masks
        } catch (e: Exception) {
            Log.e(TAG, "Get all masks failed: ${e.message}")
            listOf(generateDemoMask(0.5f, 0.5f))
        }
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        Log.d(TAG, "Resized bitmap to ${MODEL_INPUT_SIZE}x${MODEL_INPUT_SIZE}")

        val floatBuffer = FloatBuffer.allocate(1 * 3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

        for (y in 0 until MODEL_INPUT_SIZE) {
            for (x in 0 until MODEL_INPUT_SIZE) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f

                floatBuffer.put(0 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE + y * MODEL_INPUT_SIZE + x, r)
                floatBuffer.put(1 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE + y * MODEL_INPUT_SIZE + x, g)
                floatBuffer.put(2 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE + y * MODEL_INPUT_SIZE + x, b)
            }
        }

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        floatBuffer.rewind()

        return OnnxTensor.createTensor(
            ortEnvironment,
            floatBuffer,
            longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )
    }

    private fun runInference(inputTensor: OnnxTensor): OrtSession.Result {
        val inputName = ortSession!!.inputNames.iterator().next()
        Log.d(TAG, "Running with input name: $inputName")
        val inputs = mapOf(inputName to inputTensor)
        return ortSession!!.run(inputs)
    }

    private fun postProcessOutput(outputs: OrtSession.Result): List<ByteArray> {
        val masks = mutableListOf<ByteArray>()

        try {
            val output0 = outputs.get(0) as OnnxTensor
            val shape0 = output0.info.shape
            Log.d(TAG, "Processing output0 shape: ${shape0.contentToString()}")

            // Check if we have a second output (prototypes)
            if (outputs.size() < 2) {
                Log.e(TAG, "Model has only ${outputs.size()} output(s), need 2 for segmentation")
                Log.d(TAG, "Attempting to use output0 directly as mask or falling back to demo")
                return masks
            }

            val output1 = outputs.get(1) as OnnxTensor
            val protoShape = output1.info.shape
            Log.d(TAG, "Processing output1 (prototypes) shape: ${protoShape.contentToString()}")

            // Handle different output formats
            // YOLOv8-seg: output0=[1, 37, 8400], output1=[1, 32, 160, 160]
            // FastSAM might have different format

            val detectionsBuffer = output0.floatBuffer
            val prototypesBuffer = output1.floatBuffer

            // Determine format based on shapes
            val numDetections: Int
            val numChannels: Int

            if (shape0.size == 3) {
                // [batch, channels, detections] format
                numChannels = shape0[1].toInt()
                numDetections = shape0[2].toInt()
            } else {
                Log.e(TAG, "Unexpected output0 shape: ${shape0.contentToString()}")
                return masks
            }

            Log.d(TAG, "Detection format: $numChannels channels, $numDetections detections")

            val numProtos = protoShape[1].toInt()
            val maskH = protoShape[2].toInt()
            val maskW = protoShape[3].toInt()
            Log.d(TAG, "Prototype format: $numProtos protos, ${maskH}x${maskW} mask size")

            // Number of mask coefficients
            val numMaskCoeffs = numChannels - 5  // channels - (4 bbox + 1 conf)
            Log.d(TAG, "Mask coefficients per detection: $numMaskCoeffs")

            if (numMaskCoeffs != numProtos) {
                Log.w(TAG, "Mismatch: mask coeffs ($numMaskCoeffs) != protos ($numProtos)")
            }

            // Convert to arrays for easier access
            detectionsBuffer.rewind()
            val detections = FloatArray(detectionsBuffer.remaining())
            detectionsBuffer.get(detections)

            prototypesBuffer.rewind()
            val prototypes = FloatArray(prototypesBuffer.remaining())
            prototypesBuffer.get(prototypes)

            // Process detections
            val validDetections = mutableListOf<Detection>()
            var maxConf = 0f
            var totalConf = 0f

            for (i in 0 until numDetections) {
                val conf = detections[4 * numDetections + i]
                totalConf += conf
                if (conf > maxConf) maxConf = conf

                if (conf > CONFIDENCE_THRESHOLD) {
                    val cx = detections[0 * numDetections + i]
                    val cy = detections[1 * numDetections + i]
                    val w = detections[2 * numDetections + i]
                    val h = detections[3 * numDetections + i]

                    val maskCoeffs = FloatArray(numMaskCoeffs.coerceAtMost(numProtos))
                    for (j in maskCoeffs.indices) {
                        maskCoeffs[j] = detections[(5 + j) * numDetections + i]
                    }

                    validDetections.add(Detection(cx, cy, w, h, conf, maskCoeffs))
                }
            }

            Log.d(TAG, "Confidence stats: max=$maxConf, avg=${totalConf/numDetections}")
            Log.d(TAG, "Valid detections (conf > $CONFIDENCE_THRESHOLD): ${validDetections.size}")

            if (validDetections.isEmpty()) {
                Log.d(TAG, "No valid detections found")
                return masks
            }

            // Log top 5 detections
            val top5 = validDetections.sortedByDescending { it.conf }.take(5)
            for ((idx, det) in top5.withIndex()) {
                Log.d(TAG, "  Top[$idx]: conf=${det.conf}, box=(${det.cx}, ${det.cy}, ${det.w}, ${det.h})")
            }

            // Apply NMS
            val nmsDetections = nonMaxSuppression(validDetections, IOU_THRESHOLD)
            Log.d(TAG, "After NMS: ${nmsDetections.size} detections")

            // Generate masks
            for ((idx, detection) in nmsDetections.withIndex()) {
                Log.d(TAG, "Generating mask for detection $idx (conf=${detection.conf})")
                val mask = generateMaskFromPrototypes(
                    prototypes, detection.maskCoeffs, numProtos.coerceAtMost(detection.maskCoeffs.size),
                    maskH, maskW, detection
                )
                masks.add(mask)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Post-processing error: ${e.message}")
            e.printStackTrace()
        }

        return masks
    }

    private data class Detection(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val conf: Float, val maskCoeffs: FloatArray
    )

    private fun generateMaskFromPrototypes(
        prototypes: FloatArray, coefficients: FloatArray,
        numProtos: Int, maskH: Int, maskW: Int, detection: Detection
    ): ByteArray {
        val mask = ByteArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        val tempMask = FloatArray(maskH * maskW)

        // Compute mask: sum(coeff_i * prototype_i)
        for (p in 0 until numProtos) {
            val coeff = coefficients[p]
            for (y in 0 until maskH) {
                for (x in 0 until maskW) {
                    val protoIdx = p * maskH * maskW + y * maskW + x
                    if (protoIdx < prototypes.size) {
                        tempMask[y * maskW + x] += coeff * prototypes[protoIdx]
                    }
                }
            }
        }

        // Log temp mask stats
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        for (v in tempMask) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        Log.d(TAG, "  TempMask range: [$minVal, $maxVal]")

        // Log bounding box info for debugging
        val scale = maskH.toFloat() / MODEL_INPUT_SIZE
        val x1 = ((detection.cx - detection.w / 2) * scale).toInt().coerceIn(0, maskW - 1)
        val y1 = ((detection.cy - detection.h / 2) * scale).toInt().coerceIn(0, maskH - 1)
        val x2 = ((detection.cx + detection.w / 2) * scale).toInt().coerceIn(0, maskW - 1)
        val y2 = ((detection.cy + detection.h / 2) * scale).toInt().coerceIn(0, maskH - 1)
        Log.d(TAG, "  BBox in mask coords: ($x1,$y1)-($x2,$y2)")

        // Apply sigmoid and threshold to generate proper segmentation mask
        // Note: We don't clip to bounding box - the prototype coefficients already
        // encode the proper shape. Clipping would cause rectangular masks.
        val scaleToOutput = MODEL_INPUT_SIZE.toFloat() / maskH
        var positivePixels = 0

        for (y in 0 until MODEL_INPUT_SIZE) {
            for (x in 0 until MODEL_INPUT_SIZE) {
                val srcY = (y / scaleToOutput).toInt().coerceIn(0, maskH - 1)
                val srcX = (x / scaleToOutput).toInt().coerceIn(0, maskW - 1)

                val value = tempMask[srcY * maskW + srcX]
                val sigmoid = 1.0f / (1.0f + exp(-value))
                if (sigmoid > MASK_THRESHOLD) {
                    mask[y * MODEL_INPUT_SIZE + x] = 255.toByte()
                    positivePixels++
                }
            }
        }

        Log.d(TAG, "  Generated mask with $positivePixels positive pixels")
        return mask
    }

    private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.conf }
        val selected = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue
            selected.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (calculateIoU(sorted[i], sorted[j]) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return selected
    }

    private fun calculateIoU(det1: Detection, det2: Detection): Float {
        val x1 = max(det1.cx - det1.w / 2, det2.cx - det2.w / 2)
        val y1 = max(det1.cy - det1.h / 2, det2.cy - det2.h / 2)
        val x2 = min(det1.cx + det1.w / 2, det2.cx + det2.w / 2)
        val y2 = min(det1.cy + det1.h / 2, det2.cy + det2.h / 2)

        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val area1 = det1.w * det1.h
        val area2 = det2.w * det2.h
        val union = area1 + area2 - intersection

        return if (union > 0) intersection / union else 0f
    }

    private fun selectMaskAtPoint(masks: List<ByteArray>, normalizedX: Float, normalizedY: Float): ByteArray {
        val px = (normalizedX * MODEL_INPUT_SIZE).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)
        val py = (normalizedY * MODEL_INPUT_SIZE).toInt().coerceIn(0, MODEL_INPUT_SIZE - 1)
        Log.d(TAG, "Selecting mask at pixel ($px, $py)")

        var bestMask: ByteArray? = null
        var smallestArea = Int.MAX_VALUE

        for ((idx, mask) in masks.withIndex()) {
            val maskIdx = py * MODEL_INPUT_SIZE + px
            val pixelValue = mask[maskIdx].toInt() and 0xFF
            val area = mask.count { (it.toInt() and 0xFF) > 127 }
            Log.d(TAG, "  Mask[$idx]: pixel@tap=$pixelValue, area=$area")

            if (pixelValue > 127 && area < smallestArea && area > 0) {
                smallestArea = area
                bestMask = mask
            }
        }

        // If no mask at tap point, find nearest
        if (bestMask == null && masks.isNotEmpty()) {
            Log.d(TAG, "No mask at tap point, selecting largest mask")
            bestMask = masks.maxByOrNull { mask -> mask.count { (it.toInt() and 0xFF) > 127 } }
        }

        return bestMask ?: generateDemoMask(normalizedX, normalizedY)
    }

    private fun generateDemoMask(normalizedX: Float, normalizedY: Float): ByteArray {
        Log.d(TAG, "Generating DEMO mask at ($normalizedX, $normalizedY)")
        val mask = ByteArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)

        val centerX = (normalizedX * MODEL_INPUT_SIZE).toInt()
        val centerY = (normalizedY * MODEL_INPUT_SIZE).toInt()
        val radiusX = MODEL_INPUT_SIZE / 3
        val radiusY = MODEL_INPUT_SIZE / 3

        var count = 0
        for (y in 0 until MODEL_INPUT_SIZE) {
            for (x in 0 until MODEL_INPUT_SIZE) {
                val dx = (x - centerX).toFloat() / radiusX
                val dy = (y - centerY).toFloat() / radiusY
                // Use ellipse equation (dx^2 + dy^2 < 1) for circular/elliptical shape
                // instead of rectangular (abs(dx) < 1 && abs(dy) < 1)
                if (dx * dx + dy * dy < 1.0f) {
                    mask[y * MODEL_INPUT_SIZE + x] = 255.toByte()
                    count++
                }
            }
        }

        Log.d(TAG, "Demo mask created with $count pixels (elliptical)")
        return mask
    }

    fun release() {
        Log.d(TAG, "Releasing resources")
        ortSession?.close()
        ortSession = null
        ortEnvironment?.close()
        ortEnvironment = null
        isInitialized = false
    }
}
