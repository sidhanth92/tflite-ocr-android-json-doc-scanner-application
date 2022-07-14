/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package org.tensorflow.lite.examples.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.lib.docscanner.utils.ImageTextReader
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.dnn.Dnn.NMSBoxesRotated
import org.opencv.imgproc.Imgproc.*
import org.opencv.utils.Converters.vector_RotatedRect_to_Mat
import org.opencv.utils.Converters.vector_float_to_Mat
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.examples.ocr.utils.Utils
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*

/**
 * Class to run the OCR models. The OCR process is broken down into 2 stages: 1) Text detection
 * using [EAST model](https://tfhub.dev/sayakpaul/lite-model/east-text-detector/fp16/1) 2) Text
 * recognition using
 * [Keras OCR model](https://tfhub.dev/tulasiram58827/lite-model/keras-ocr/float16/2)
 */
class OCRModelExecutor(context: Context, private var useGPU: Boolean = false) : AutoCloseable {
    private var gpuDelegate: GpuDelegate? = null

    private val recognitionResult: ByteBuffer
    private val detectionInterpreter: Interpreter
    private val recognitionInterpreter: Interpreter
    private var ratioHeight = 0.toFloat()
    private var ratioWidth = 0.toFloat()
    private var indicesMat: MatOfInt
    private var boundingBoxesMat: MatOfRotatedRect
    private var ocrResults: HashMap<String, Int>
    private val mImageTextReader: ImageTextReader? = null

    init {
        try {
            Log.d("msg", "init------------------------")
            if (!OpenCVLoader.initDebug()) throw Exception("Unable to load OpenCV")
            else Log.d(TAG, "OpenCV loaded")
        } catch (e: Exception) {
            val exceptionLog = "something went wrong: ${e.message}"
            Log.d(TAG, exceptionLog)
        }

        detectionInterpreter = getInterpreter(context, textDetectionModel, useGPU)
        // Recognition model requires Flex so we disable GPU delegate no matter user choice
        recognitionInterpreter = getInterpreter(context, textRecognitionModel, false)

        recognitionResult = ByteBuffer.allocateDirect(recognitionModelOutputSize * 8)
        recognitionResult.order(ByteOrder.nativeOrder())
        indicesMat = MatOfInt()
        boundingBoxesMat = MatOfRotatedRect()
        ocrResults = HashMap<String, Int>()
    }

    fun execute(data: Bitmap, idCardName:String): ModelExecutionResult {
        Log.d("msg", "execute-------------------")
        try {
            ratioHeight = data.height.toFloat() / detectionImageHeight
            ratioWidth = data.width.toFloat() / detectionImageWidth
            ocrResults.clear()

            detectTexts(data)

            val bitmapWithBoundingBoxes = recognizeTexts(data, boundingBoxesMat, indicesMat, idCardName)

            return ModelExecutionResult(bitmapWithBoundingBoxes, "OCR result", ocrResults)
        } catch (e: Exception) {
            Log.d("msg", "execute2-------------------")
            val exceptionLog = "something went wrong: ${e.message}"
            Log.d(TAG, exceptionLog)

            val emptyBitmap = ImageUtils.createEmptyBitmap(displayImageSize, displayImageSize)
            return ModelExecutionResult(emptyBitmap, exceptionLog, HashMap<String, Int>())
        }
    }

    private fun detectTexts(data: Bitmap) {
        val detectionTensorImage =
            ImageUtils.bitmapToTensorImageForDetection(
                data,
                detectionImageWidth,
                detectionImageHeight,
                detectionImageMeans,
                detectionImageStds
            )

        val detectionInputs = arrayOf(detectionTensorImage.buffer.rewind())
        val detectionOutputs: HashMap<Int, Any> = HashMap<Int, Any>()

        val detectionScores =
            Array(1) { Array(detectionOutputNumRows) { Array(detectionOutputNumCols) { FloatArray(1) } } }
        val detectionGeometries =
            Array(1) { Array(detectionOutputNumRows) { Array(detectionOutputNumCols) { FloatArray(5) } } }
        detectionOutputs.put(0, detectionScores)
        detectionOutputs.put(1, detectionGeometries)

        detectionInterpreter.runForMultipleInputsOutputs(detectionInputs, detectionOutputs)

        val transposeddetectionScores =
            Array(1) { Array(1) { Array(detectionOutputNumRows) { FloatArray(detectionOutputNumCols) } } }
        val transposedDetectionGeometries =
            Array(1) { Array(5) { Array(detectionOutputNumRows) { FloatArray(detectionOutputNumCols) } } }

        // transpose detection output tensors
        for (i in 0 until transposeddetectionScores[0][0].size) {
            for (j in 0 until transposeddetectionScores[0][0][0].size) {
                for (k in 0 until 1) {
                    transposeddetectionScores[0][k][i][j] = detectionScores[0][i][j][k]
                }
                for (k in 0 until 5) {
                    transposedDetectionGeometries[0][k][i][j] = detectionGeometries[0][i][j][k]
                }
            }
        }

        val detectedRotatedRects = ArrayList<RotatedRect>()
        val detectedConfidences = ArrayList<Float>()


        for (y in 0 until transposeddetectionScores[0][0].size) {
            val detectionScoreData = transposeddetectionScores[0][0][y]
            val detectionGeometryX0Data = transposedDetectionGeometries[0][0][y]
            val detectionGeometryX1Data = transposedDetectionGeometries[0][1][y]
            val detectionGeometryX2Data = transposedDetectionGeometries[0][2][y]
            val detectionGeometryX3Data = transposedDetectionGeometries[0][3][y]
            val detectionRotationAngleData = transposedDetectionGeometries[0][4][y]

            for (x in 0 until transposeddetectionScores[0][0][0].size) {
                if (detectionScoreData[x] < 0.5) {
                    continue
                }

                // Compute the rotated bounding boxes and confiences (heavily based on OpenCV example):
                // https://github.com/opencv/opencv/blob/master/samples/dnn/text_detection.py
                val offsetX = x * 4.0
                val offsetY = y * 4.0

                val h = detectionGeometryX0Data[x] + detectionGeometryX2Data[x]
                val w = detectionGeometryX1Data[x] + detectionGeometryX3Data[x]

                val angle = detectionRotationAngleData[x]
                val cos = Math.cos(angle.toDouble())
                val sin = Math.sin(angle.toDouble())

                val offset =
                    Point(
                        offsetX + cos * detectionGeometryX1Data[x] + sin * detectionGeometryX2Data[x],
                        offsetY - sin * detectionGeometryX1Data[x] + cos * detectionGeometryX2Data[x]
                    )
                val p1 = Point(-sin * h + offset.x, -cos * h + offset.y)
                val p3 = Point(-cos * w + offset.x, sin * w + offset.y)
                val center = Point(0.5 * (p1.x + p3.x), 0.5 * (p1.y + p3.y))

                val textDetection =
                    RotatedRect(
                        center,
                        Size(w.toDouble(), h.toDouble()),
                        (-1 * angle * 180.0 / Math.PI)
                    )
                detectedRotatedRects.add(textDetection)
                detectedConfidences.add(detectionScoreData[x])
            }
        }

        val detectedConfidencesMat = MatOfFloat(vector_float_to_Mat(detectedConfidences))

        boundingBoxesMat = MatOfRotatedRect(vector_RotatedRect_to_Mat(detectedRotatedRects))
        NMSBoxesRotated(
            boundingBoxesMat,
            detectedConfidencesMat,
            detectionConfidenceThreshold.toFloat(),
            detectionNMSThreshold.toFloat(),
            indicesMat
        )
    }

    private fun getJsonResult(pattern: Regex, recognizedText: String, jkey: String): String {
        println("********************************")
        println(recognizedText)
        println("_____________________________________")
        var tmpStr = ""
        val p = pattern
        val pfoundall = p.findAll(recognizedText)
        var m = ""
        pfoundall.forEach { f ->
            m = m + f.value + ","
        }
        tmpStr = jkey + ": " + m + ","
        println("&&&&&&&&&&&")
        println(tmpStr)
        println("********************************")
        return tmpStr
    }


    private fun recognizeTexts(
        data: Bitmap,
        boundingBoxesMat: MatOfRotatedRect,
        indicesMat: MatOfInt,
        idCardName: String
    ): Bitmap {
        var bitmapWithBoundingBoxes = data.copy(Bitmap.Config.ARGB_8888, true)
        //apply peprocessing
        bitmapWithBoundingBoxes = Utils.preProcessBitmap(bitmapWithBoundingBoxes)
        //TODO: tesserract implementation
         var full_text = mImageTextReader.getTextFromBitmap(bitmapWithBoundingBoxes)
        Log.d("msg",full_text)
        //TODO: if tessarct implemented then -->below section can be commented upto end
        /*val canvas = Canvas(bitmapWithBoundingBoxes)
        val paint = Paint()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 10.toFloat()
        paint.setColor(Color.GREEN)

        var lastOutput: String
        lastOutput = ""
        for (i in indicesMat.toArray()) {
            val boundingBox = boundingBoxesMat.toArray()[i]
            val targetVertices = ArrayList<Point>()
            targetVertices.add(Point(0.toDouble(), (recognitionImageHeight - 1).toDouble()))
            targetVertices.add(Point(0.toDouble(), 0.toDouble()))
            targetVertices.add(Point((recognitionImageWidth - 1).toDouble(), 0.toDouble()))
            targetVertices.add(
                Point(
                    (recognitionImageWidth - 1).toDouble(),
                    (recognitionImageHeight - 1).toDouble()
                )
            )

            val srcVertices = ArrayList<Point>()

            val boundingBoxPointsMat = Mat()
            boxPoints(boundingBox, boundingBoxPointsMat)
            for (j in 0 until 4) {
                srcVertices.add(
                    Point(
                        boundingBoxPointsMat.get(j, 0)[0] * ratioWidth,
                        boundingBoxPointsMat.get(j, 1)[0] * ratioHeight
                    )
                )
                if (j != 0) {
                    canvas.drawLine(
                        (boundingBoxPointsMat.get(j, 0)[0] * ratioWidth).toFloat(),
                        (boundingBoxPointsMat.get(j, 1)[0] * ratioHeight).toFloat(),
                        (boundingBoxPointsMat.get(j - 1, 0)[0] * ratioWidth).toFloat(),
                        (boundingBoxPointsMat.get(j - 1, 1)[0] * ratioHeight).toFloat(),
                        paint
                    )
                }
            }
            canvas.drawLine(
                (boundingBoxPointsMat.get(0, 0)[0] * ratioWidth).toFloat(),
                (boundingBoxPointsMat.get(0, 1)[0] * ratioHeight).toFloat(),
                (boundingBoxPointsMat.get(3, 0)[0] * ratioWidth).toFloat(),
                (boundingBoxPointsMat.get(3, 1)[0] * ratioHeight).toFloat(),
                paint
            )

            val srcVerticesMat =
                MatOfPoint2f(srcVertices[0], srcVertices[1], srcVertices[2], srcVertices[3])
            val targetVerticesMat =
                MatOfPoint2f(
                    targetVertices[0],
                    targetVertices[1],
                    targetVertices[2],
                    targetVertices[3]
                )
            val rotationMatrix = getPerspectiveTransform(srcVerticesMat, targetVerticesMat)
            val recognitionBitmapMat = Mat()
            val srcBitmapMat = Mat()
            bitmapToMat(data, srcBitmapMat)
            warpPerspective(
                srcBitmapMat,
                recognitionBitmapMat,
                rotationMatrix,
                Size(recognitionImageWidth.toDouble(), recognitionImageHeight.toDouble())
            )

            val recognitionBitmap =
                ImageUtils.createEmptyBitmap(
                    recognitionImageWidth,
                    recognitionImageHeight,
                    0,
                    Bitmap.Config.ARGB_8888
                )
            matToBitmap(recognitionBitmapMat, recognitionBitmap)

            val recognitionTensorImage =
                ImageUtils.bitmapToTensorImageForRecognition(
                    recognitionBitmap,
                    recognitionImageWidth,
                    recognitionImageHeight,
                    recognitionImageMean,
                    recognitionImageStd
                )

            recognitionResult.rewind()
            recognitionInterpreter.run(recognitionTensorImage.buffer, recognitionResult)

            var recognizedText = ""
            for (k in 0 until recognitionModelOutputSize) {
                var alphabetIndex = recognitionResult.getInt(k * 8)
                if (alphabetIndex in 0..alphabets.length - 1)
                    recognizedText = recognizedText + alphabets[alphabetIndex]
            }
            Log.d("Recognition result:", recognizedText)
            if (recognizedText != "") {
                //ocrResults.put(recognizedText, getRandomColor())
            }
            lastOutput = lastOutput + " " + recognizedText
        }*/
        //TODO: if tessarct implemented <-- comment above code

        //var idcardname = "Kenya_front"

        Log.d(TAG , idCardName)
        var Result: String
        Result = "{"
        when (idCardName) {
            "Uganda" -> {
                //pattern 1 id_number
                val p1 = "\\s\\d{9}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  doi
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "doi")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = "\\sm\\s|\\sf\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")

                //pattern 5  nin
                val p5 = "\\s[A-Za-z0-9]{14}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "nin")
                //pattern 6  district
                val p6 = "\\s[A-Za-z]{3}\\s\n".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")
            }
            "Uganda_DL" -> {
                //TODO:
                // pattern 1 card_no
                val p1 = "\\s\\d{8}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  doe
                val p2 = "\\s\\d{2}[./-]\\d{2}[./-]\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "doe")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = "\\sMale\\s|\\sFemale\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")
                //pattern 5  district
                val p5 = "\\s[A-Za-z]+\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "district")

            }
            "uganda_passport" -> {
                //TODO:
                //pattern 1 type
                val p1 = "\\sP\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "type")
                //pattern 2  doi
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "doi")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  country_code
                val p4 = "\\sKEN\\s|\\sTZA\\s|\\sUGA\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "ccode")

                //pattern 5  passport_number
                val p5 = "\\s[A-Za-z0-9]{8}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "ppnum")
                //pattern 6  nationality
                val p6 = "\\s[A-Za-z]+\\s\n".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")

            }
            "Kenya_Front" -> {
                val p1 = "\\s\\d{9}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  dob
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "dob")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = "\\sM\\s|\\sF\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")

                //pattern 5  doi
                val p5 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "doi")
                //pattern 6  district
                val p6 = "\\s\\w+\\s".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")
                //pattern 7  serial_no
                val p7 = "\\s[a-z]{3}\\s".toRegex()
                Result = Result + getJsonResult(p7, lastOutput, "nationality")
                //pattern 8  poi
                val p8 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p8, lastOutput, "poi")
            }
            //TODO:
            "Kenya_Back" -> {
                val p1 = "\\s\\d{9}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  dob
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "dob")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = "\\sM\\s|\\sF\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")

                //pattern 5  doi
                val p5 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "doi")
                //pattern 6  district
                val p6 = "\\s\\w+\\s".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")
                //pattern 7  serial_no
                val p7 = "\\s[a-z]{3}\\s".toRegex()
                Result = Result + getJsonResult(p7, lastOutput, "nationality")
                //pattern 8  poi
                val p8 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p8, lastOutput, "poi")
            }
            "Kenya_DL" -> {
                //TODO:
                val p1 = "\\s\\d{8}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  doe
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "dates")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = " \\sMALE\\s|\\sFEMALE\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")

                //pattern 5  bloodgroup
                val p5 = "\\s[A|B|AB|O][+|-]\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "bgroup")
                //pattern 6  district
                val p6 = "\\s[A-Za-z]+\\s\n".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")
                //pattern 7  license_no.
                val p7 = "\\s[A-Za-z0-9]{6}\\s".toRegex()
                Result = Result + getJsonResult(p7, lastOutput, "license_no")
            }
            "ken_for_back" -> {
                //TODO:
                //pattern 1 im_status
                val p1 = "\\s[A-Za-z]{3}[./-][A-Za-z]{1}[./-]\\d{6}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "im_status")
                //pattern 2  rnumber
                val p2 = "\\d{7}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "rnum")
                //pattern 3  passport_number
                val p3 = "\\s[A-Za-z0-9]{8}\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "ppnum")
            }
            "kenya_passport" -> {
                //TODO:
                //pattern 1 type
                val p1 = "\\sP\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "type")
                //pattern 2  doi
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "doi")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  country_code
                val p4 = "\\sKEN\\s|\\sTZA\\s|\\sUGA\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "ccode")

                //pattern 5  passport_number
                val p5 = "\\s[A-Za-z0-9]{8}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "ppnum")
                //pattern 6  nationality
                val p6 = "\\s[A-Za-z]+\\s\n".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")

            }
            "tanzania_passport" -> {
                //TODO:
                //pattern 1 type
                val p1 = "\\sP\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "type")
                //pattern 2  doi
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "doi")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  country_code
                val p4 = "\\sKEN\\s|\\sTZA\\s|\\sUGA\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "ccode")

                //pattern 5  passport_number
                val p5 = "\\s[A-Za-z0-9]{8}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "ppnum")
                //pattern 6  nationality
                val p6 = "\\s[A-Za-z]+\\s\n".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")

            }
            "" -> {//kenya_front
                val p1 = "\\s\\d{9}\\s".toRegex()
                Result = Result + getJsonResult(p1, lastOutput, "id_number")
                //pattern 2  dob
                val p2 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p2, lastOutput, "dob")
                //pattern 3  name
                val p3 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p3, lastOutput, "name")
                //pattern 4  GEN
                val p4 = "\\sM\\s|\\sF\\s".toRegex()
                Result = Result + getJsonResult(p4, lastOutput, "GEN")

                //pattern 5  doi
                val p5 = "\\s\\d{2}.\\d{2}.\\d{4}\\s".toRegex()
                Result = Result + getJsonResult(p5, lastOutput, "doi")
                //pattern 6  district
                val p6 = "\\s\\w+\\s".toRegex()
                Result = Result + getJsonResult(p6, lastOutput, "district")
                //pattern 7  serial_no
                val p7 = "\\s[a-z]{3}\\s".toRegex()
                Result = Result + getJsonResult(p7, lastOutput, "nationality")
                //pattern 8  poi
                val p8 = "\\s[a-z]+\\s".toRegex()
                Result = Result + getJsonResult(p8, lastOutput, "poi")
            }
        }

        Result = Result + "}"




        ocrResults.put(Result, getRandomColor())


        return bitmapWithBoundingBoxes
    }

    // base:
    // https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/java/demo/app/src/main/java/com/example/android/tflitecamerademo/ImageClassifier.java
    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val retFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        fileDescriptor.close()
        return retFile
    }

    @Throws(IOException::class)
    private fun getInterpreter(
        context: Context,
        modelName: String,
        useGpu: Boolean = false
    ): Interpreter {
        val tfliteOptions = Interpreter.Options()
        tfliteOptions.setNumThreads(numberThreads)

        gpuDelegate = null
        if (useGpu) {
            gpuDelegate = GpuDelegate()
            tfliteOptions.addDelegate(gpuDelegate)
        }

        return Interpreter(loadModelFile(context, modelName), tfliteOptions)
    }

    override fun close() {
        Log.d("msg", "close--------------------------------")
        detectionInterpreter.close()
        recognitionInterpreter.close()
        if (gpuDelegate != null) {
            gpuDelegate!!.close()
        }
    }

    fun getRandomColor(): Int {
        Log.d("msg", "getRandomColor------------------------------")
        val random = Random()
        return Color.argb(
            (128),
            (255 * random.nextFloat()).toInt(),
            (255 * random.nextFloat()).toInt(),
            (255 * random.nextFloat()).toInt()
        )
    }

    companion object {
        public const val TAG = "msgOCRModelExecutor"

        //private const val textDetectionModel = "chitresh_model.tflite"
        private const val textDetectionModel = "text_detection.tflite"
        private const val textRecognitionModel = "text_recognition.tflite"
        private const val numberThreads = 4
        private const val alphabets = "0123456789abcdefghijklmnopqrstuvwxyz"
        private const val displayImageSize = 257
        private const val detectionImageHeight = 320
        private const val detectionImageWidth = 320
        private val detectionImageMeans =
            floatArrayOf(103.94.toFloat(), 116.78.toFloat(), 123.68.toFloat())
        private val detectionImageStds = floatArrayOf(1.toFloat(), 1.toFloat(), 1.toFloat())
        private val detectionOutputNumRows = 80
        private val detectionOutputNumCols = 80
        private val detectionConfidenceThreshold = 0.5
        private val detectionNMSThreshold = 0.4
        private const val recognitionImageHeight = 31
        private const val recognitionImageWidth = 200
        private const val recognitionImageMean = 0.toFloat()
        private const val recognitionImageStd = 255.toFloat()
        private const val recognitionModelOutputSize = 48
    }
}
