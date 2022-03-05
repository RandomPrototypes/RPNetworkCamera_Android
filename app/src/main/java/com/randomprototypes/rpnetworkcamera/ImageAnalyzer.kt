package com.randomprototypes.rpnetworkcamera

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.*
import android.graphics.ImageFormat

import android.graphics.YuvImage





class ImageAnalyser(mainActivity: MainActivity) : ImageAnalysis.Analyzer {
    private val mainActivity : MainActivity = mainActivity
    private var reuseBuffer: ByteBuffer? = null
    public var frame_id : Int = 0

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    //https://stackoverflow.com/questions/56772967/converting-imageproxy-to-bitmap
    fun ImageProxy.toBitmap(invertColor : Boolean): Bitmap? {
        val nv21 = yuv420888ToNv21(this, invertColor)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        return yuvImage.toBitmap()
    }

    fun ImageProxy.toJpeg(invertColor : Boolean) : ByteArray {
        val nv21 = yuv420888ToNv21(this, invertColor)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out))
            Log.e("", "can not compress to jpeg")
        return out.toByteArray()
    }

    private fun YuvImage.toBitmap(): Bitmap? {
        val out = ByteArrayOutputStream()
        if (!compressToJpeg(Rect(0, 0, width, height), 100, out))
            return null
        val imageBytes: ByteArray = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun yuv420888ToNv21(image: ImageProxy, invertColor : Boolean): ByteArray {
        val pixelCount = image.cropRect.width() * image.cropRect.height()
        Log.e("", "pixelCount: "+pixelCount)
        val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
        val outputBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
        imageToByteBuffer(image, outputBuffer, pixelCount, invertColor)
        return outputBuffer
    }

    private fun imageToByteBuffer(image: ImageProxy, outputBuffer: ByteArray, pixelCount: Int, invertColor : Boolean) {
        assert(image.format == ImageFormat.YUV_420_888)

        val imageCrop = image.cropRect
        val imagePlanes = image.planes

        imagePlanes.forEachIndexed { planeIndex, plane ->
            // How many values are read in input for each output value written
            // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
            //
            // Y Plane            U Plane    V Plane
            // ===============    =======    =======
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y    U U U U    V V V V
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            val outputStride: Int

            // The index in the output buffer the next value will be written at
            // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
            //
            // First chunk        Second chunk
            // ===============    ===============
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y    V U V U V U V U
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            // Y Y Y Y Y Y Y Y
            var outputOffset: Int

            when (planeIndex) {
                0 -> {
                    outputStride = 1
                    outputOffset = 0
                }
                1 -> {
                    outputStride = 2
                    // For NV21 format, U is in odd-numbered indices
                    if(invertColor)
                        outputOffset = pixelCount
                    else outputOffset = pixelCount + 1
                }
                2 -> {
                    outputStride = 2
                    // For NV21 format, V is in even-numbered indices
                    if(invertColor)
                        outputOffset = pixelCount + 1
                    else outputOffset = pixelCount
                }
                else -> {
                    // Image contains more than 3 planes, something strange is going on
                    return@forEachIndexed
                }
            }

            val planeBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // We have to divide the width and height by two if it's not the Y plane
            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }

            val planeWidth = planeCrop.width()
            val planeHeight = planeCrop.height()

            // Intermediate buffer used to store the bytes of each row
            val rowBuffer = ByteArray(plane.rowStride)

            // Size of each row in bytes
            val rowLength = if (pixelStride == 1 && outputStride == 1) {
                planeWidth
            } else {
                // Take into account that the stride may include data from pixels other than this
                // particular plane and row, and that could be between pixels and not after every
                // pixel:
                //
                // |---- Pixel stride ----|                    Row ends here --> |
                // | Pixel 1 | Other Data | Pixel 2 | Other Data | ... | Pixel N |
                //
                // We need to get (N-1) * (pixel stride bytes) per row + 1 byte for the last pixel
                (planeWidth - 1) * pixelStride + 1
            }

            for (row in 0 until planeHeight) {
                // Move buffer position to the beginning of this row
                planeBuffer.position(
                    (row + planeCrop.top) * rowStride + planeCrop.left * pixelStride)

                if (pixelStride == 1 && outputStride == 1) {
                    // When there is a single stride value for pixel and output, we can just copy
                    // the entire row in a single step
                    planeBuffer.get(outputBuffer, outputOffset, rowLength)
                    outputOffset += rowLength
                } else {
                    // When either pixel or output have a stride > 1 we must copy pixel by pixel
                    planeBuffer.get(rowBuffer, 0, rowLength)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
            }
        }
    }

    private fun YUV_420_888toNV21(image: ImageProxy): ByteArray {
        val yBuffer: ByteBuffer = image.planes[0].buffer
        val uBuffer: ByteBuffer = image.planes[1].buffer
        val vBuffer: ByteBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        Log.e("", "buf size: "+nv21.size)
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        return nv21
    }

    private fun NV21toJPEG(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuv.compressToJpeg(Rect(0, 0, width, height), 100, out)
        return out.toByteArray()
    }

    override fun analyze(image: ImageProxy) {
        val timestamp = mainActivity.getTimestampMs()
        //fix for https://issuetracker.google.com/issues/216279982
        image.setCropRect(Rect(0,0,image.width,image.height))

        //yuv420888ToNv21(image, false)//buffer.toByteArray()
        //Log.e("", image.format.toString()+" "+image.width+" "+image.height+" "+data.size+" strides "+image.planes[0].pixelStride+" "+image.planes[0].rowStride)
        //Log.e("", "plane0 "+plane0.pixelStride+" "+plane0.rowStride+" "+plane0.buffer.remaining()+", plane1 "+plane1.pixelStride+" "+plane1.rowStride+" "+plane1.buffer.remaining()+", plane2 "+plane2.pixelStride+" "+plane2.rowStride+" "+plane2.buffer.remaining())
        //val pixels = data.map { it.toInt() and 0xFF }

        /*val isRecording = mainActivity.isRecording
        if(isRecording)
        {
            if(mainActivity.videoEncoder == null) {
                mainActivity.videoEncoder =
                    BitmapToVideoEncoder { mainActivity.captureImageSemaphore.release() }
                mainActivity.videoEncoder?.startEncoding(
                    image.width,
                    image.height,
                    File(mainActivity.videoRecordFilename)
                );
            }
            if(image != null) {
                mainActivity.videoEncoder?.queueFrame(yuv420888ToNv21(image, true));
            }
        } else if(mainActivity.videoEncoder != null){
            mainActivity.videoEncoder?.stopEncoding();
            mainActivity.videoEncoder = null
        }*/


        if(mainActivity.imgCapRequested) {
            Log.d("", "cropRect: "+image.cropRect.toString())
            val data = image.toJpeg(false)//NV21toJPEG(YUV_420_888toNV21(image), image.width, image.height)
            /*val bitmap = image.toBitmap(false)
            if(bitmap != null) {
                val scaledBitmap = Bitmap.createScaledBitmap(
                    bitmap,
                    mainActivity.imgCapWidth,
                    mainActivity.imgCapHeight,
                    true
                )
                Log.e("", "cols : "+bitmap.getRowBytes()+" rows: "+bitmap.getHeight())
                val size: Int = bitmap.getRowBytes() * bitmap.getHeight()
                val byteBuffer = ByteBuffer.allocate(size)
                bitmap.copyPixelsToBuffer(byteBuffer)
                val byteArray = byteBuffer.array()

                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                mainActivity.imgCapturedByteArray = image.toJpeg(false) //stream.toByteArray()
                mainActivity.imgCapRequested = false
                mainActivity.captureImageSemaphore.release()
            }*/
                Log.e("", "length : "+data?.size)
            mainActivity.imgCapturedTimestamp = timestamp
            mainActivity.imgCapturedFrameId = frame_id
            mainActivity.imgCapturedByteArray = data //stream.toByteArray()
            mainActivity.imgCapRequested = false
            mainActivity.captureImageSemaphore.release()
        }

        image.close()
        frame_id++
    }
}