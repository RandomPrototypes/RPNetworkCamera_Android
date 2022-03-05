package com.randomprototypes.rpnetworkcamera

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore

class ServerHandler(serverSock : ServerSocket, mainActivity : MainActivity)
{
    private val mainActivity : MainActivity = mainActivity
    private val serverSock : ServerSocket = serverSock
    private val captureImageSemaphore : Semaphore = mainActivity.captureImageSemaphore
    private val captureVideoSemaphore : Semaphore = mainActivity.captureVideoSemaphore
    private var running: Boolean = false

    enum class CameraCmd(val value: Int) {
        EXIT(100),
        LIST_CAMERAS(200),
        TIMESTAMP(300),
        START_RECORDING(400),
        STOP_RECORDING(500),
        CAPTURE_IMG(600);

        companion object {
            fun fromInt(value: Int) = CameraCmd.values().first { it.value == value }
        }
    }

    private fun writeInt32(writer: OutputStream, value : Int)
    {
        val buf = ByteBuffer.allocate(4).putInt(value).order(ByteOrder.BIG_ENDIAN)
        writer.write(buf.array());
    }
    private fun writeInt64(writer: OutputStream, value : Long)
    {
        val buf = ByteBuffer.allocate(8).putLong(value).order(ByteOrder.BIG_ENDIAN)
        writer.write(buf.array());
    }
    private fun readInt32(reader: InputStream) : Int
    {
        var data = ByteArray(4)
        reader.read(data)
        val buf = ByteBuffer.wrap(data)
        return buf.int
    }
    private fun readInt64(reader: InputStream) : Long
    {
        var data = ByteArray(8)
        reader.read(data)
        val buf = ByteBuffer.wrap(data)
        return buf.long
    }

    private fun readString(reader: InputStream) : String
    {
        val size = readInt32(reader)
        var data = ByteArray(size)
        reader.read(data)
        return String(data)
    }

    fun run()
    {
        captureImageSemaphore.acquireUninterruptibly()
        captureVideoSemaphore.acquireUninterruptibly()
        while(true) {
            val client = serverSock.accept()
            running = true
            val reader: InputStream = client.getInputStream()
            val writer: OutputStream = client.getOutputStream()
            while(running)
            {
                try {
                    val command = readInt32(reader)
                    Log.e("", "command: "+command)
                    val packetLength = readInt64(reader)
                    Log.e("", "packetLength: "+packetLength)
                    if (command == CameraCmd.EXIT.value) {
                        writeInt32(writer, CameraCmd.EXIT.value)
                        running = false
                        client.close()
                    } else if(command == CameraCmd.LIST_CAMERAS.value) {
                        Log.e("", "LIST_CAMERAS")
                        writeInt32(writer, CameraCmd.LIST_CAMERAS.value)
                        var camList : Array<String?> = arrayOfNulls(2);
                        camList[0] = "id:0\n"+
                                     "name:back\n"+
                                     "desc:back camera\n";
                        camList[1] = "id:1\n"+
                                     "name:front\n"+
                                     "desc:front camera\n";
                        camList[0]?.let { Log.e("", it) }
                        camList[1]?.let { Log.e("", it) }
                        writeInt32(writer, camList.size)
                        for(camInfo in camList)
                        {
                            if(camInfo != null) {
                                writeInt32(writer, camInfo.length)
                                writer.write(camInfo.toByteArray())
                            } else {
                                writeInt32(writer, 0)
                            }
                        }
                    } else if (command == CameraCmd.CAPTURE_IMG.value){
                        writeInt32(writer, CameraCmd.CAPTURE_IMG.value)
                        mainActivity.imgCapWidth = readInt32(reader)
                        mainActivity.imgCapHeight = readInt32(reader)
                        mainActivity.imgCapFormat = readString(reader)
                        mainActivity.imgCapRequested = true

                        Log.e("", "capture "+mainActivity.imgCapWidth+" "+mainActivity.imgCapHeight+" "+mainActivity.imgCapFormat )

                        captureImageSemaphore.acquireUninterruptibly()
                        val byteArray = mainActivity.imgCapturedByteArray
                        if(byteArray != null) {
                            Log.e("", "send "+byteArray.size+" timestamp "+mainActivity.imgCapturedTimestamp)
                            writeInt32(writer, mainActivity.imgCapturedFrameId)
                            writeInt64(writer, mainActivity.imgCapturedTimestamp)
                            writeInt64(writer, byteArray.size.toLong())
                            writer.write(byteArray)
                        } else {
                            writeInt32(writer, 0)
                            writeInt64(writer, 0)
                            writeInt64(writer, 0)
                        }
                    } else if(command == CameraCmd.TIMESTAMP.value) {
                        val timestamp = mainActivity.getTimestampMs()
                        writeInt32(writer, CameraCmd.TIMESTAMP.value)
                        writeInt64(writer, timestamp)
                    } else if(command == CameraCmd.START_RECORDING.value) {
                        writeInt32(writer, CameraCmd.START_RECORDING.value)
                        mainActivity.videoRecordFilename = mainActivity.getOutputDirectory().absolutePath+"/test.mp4"
                        mainActivity.isRecording = true
                        mainActivity.startRecording()
                    } else if(command == CameraCmd.STOP_RECORDING.value) {
                        writeInt32(writer, CameraCmd.STOP_RECORDING.value)
                        val filename = mainActivity.videoRecordFilename
                        mainActivity.videoRecordFilename = ""
                        mainActivity.isRecording = false
                        mainActivity.currentRecording?.stop()
                        captureVideoSemaphore.acquireUninterruptibly()
                        Log.e("", "transfer file")
                        val file = File(filename)
                        val fileSize = file.length()
                        val fileInputStream = file.inputStream()
                        writeInt64(writer, mainActivity.videoRecordStartTimestamp)
                        writeInt64(writer, fileSize)
                        //writer.write(data)
                        var sizeRead : Long = 0
                        var data = ByteArray(10240)
                        while(sizeRead < fileSize)
                        {
                            var len = fileSize - sizeRead
                            if(len > data.size)
                                len = data.size.toLong()
                            fileInputStream.read(data, 0, len.toInt())
                            writer.write(data, 0, len.toInt())
                            sizeRead += len
                        }
                    } else {
                        Log.e("", "unknown command $command")
                        var sizeRead : Long = 0;
                        var data = ByteArray(1024)
                        while(sizeRead < packetLength)
                        {
                            var len = packetLength - sizeRead
                            if(len > data.size)
                                len = data.size.toLong()
                            reader.read(data, 0, len.toInt())
                            sizeRead += len
                        }
                        writeInt32(writer, 0)
                    }
                } catch (ex: Exception) {
                    running = false
                    Log.e("", "exception: "+ex.toString())
                    client.close()
                } finally {

                }
            }
        }
    }
}