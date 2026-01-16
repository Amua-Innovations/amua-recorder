package com.amua.audiodownloader.audio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes audio samples to a WAV file.
 * Supports writing the complete file at once or streaming with header patching.
 */
class WavFileWriter(private val context: Context) {

    companion object {
        private const val TAG = "WavFileWriter"
        private const val WAV_HEADER_SIZE = 44
    }

    /**
     * Save audio samples to a WAV file.
     *
     * @param samples The audio samples (16-bit signed integers)
     * @param sampleRate The sample rate in Hz
     * @param numChannels Number of audio channels (1 for mono)
     * @param bitsPerSample Bits per sample (16)
     * @param filename Optional filename (without extension). If null, generates timestamp-based name.
     * @param outputDirectory Optional output directory. If null, uses default directory.
     * @return The File object if successful, null otherwise.
     */
    fun saveToFile(
        samples: ShortArray,
        sampleRate: Int = AudioDataHandler.SAMPLE_RATE,
        numChannels: Int = AudioDataHandler.NUM_CHANNELS,
        bitsPerSample: Int = AudioDataHandler.BITS_PER_SAMPLE,
        filename: String? = null,
        outputDirectory: File? = null
    ): File? {
        if (samples.isEmpty()) {
            Log.w(TAG, "No samples to save")
            return null
        }

        val actualFilename = filename ?: generateFilename()
        val dir = outputDirectory ?: getOutputDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "$actualFilename.wav")

        return try {
            FileOutputStream(file).use { fos ->
                // Write WAV header
                val header = createWavHeader(
                    dataSize = samples.size * 2, // 2 bytes per sample
                    sampleRate = sampleRate,
                    numChannels = numChannels,
                    bitsPerSample = bitsPerSample
                )
                fos.write(header)

                // Write audio data (little-endian)
                val dataBuffer = ByteBuffer.allocate(samples.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (sample in samples) {
                    dataBuffer.putShort(sample)
                }
                fos.write(dataBuffer.array())
            }

            Log.i(TAG, "Saved ${samples.size} samples to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV file", e)
            null
        }
    }

    /**
     * Create a WAV file header.
     */
    private fun createWavHeader(
        dataSize: Int,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val fileSize = dataSize + WAV_HEADER_SIZE - 8

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray())       // ChunkID
        header.putInt(fileSize)                 // ChunkSize
        header.put("WAVE".toByteArray())       // Format

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray())       // Subchunk1ID
        header.putInt(16)                       // Subchunk1Size (16 for PCM)
        header.putShort(1)                      // AudioFormat (1 = PCM)
        header.putShort(numChannels.toShort()) // NumChannels
        header.putInt(sampleRate)               // SampleRate
        header.putInt(byteRate)                 // ByteRate
        header.putShort(blockAlign.toShort())  // BlockAlign
        header.putShort(bitsPerSample.toShort()) // BitsPerSample

        // "data" sub-chunk
        header.put("data".toByteArray())       // Subchunk2ID
        header.putInt(dataSize)                 // Subchunk2Size

        return header.array()
    }

    /**
     * Get the output directory for recordings.
     */
    fun getOutputDirectory(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "AmuaRecordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Get a File object for the given filename.
     */
    private fun getOutputFile(filename: String): File {
        val dir = getOutputDirectory()
        return File(dir, "$filename.wav")
    }

    /**
     * Generate a timestamp-based filename.
     */
    private fun generateFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "amua_recording_${dateFormat.format(Date())}"
    }

    /**
     * Get a list of all saved recordings.
     */
    fun getRecordings(): List<File> {
        val dir = getOutputDirectory()
        return dir.listFiles { file -> file.extension == "wav" }?.toList() ?: emptyList()
    }
}

/**
 * Streaming WAV writer that supports writing samples incrementally
 * and patching the header at the end.
 */
class StreamingWavWriter(private val file: File) {

    companion object {
        private const val TAG = "StreamingWavWriter"
        private const val WAV_HEADER_SIZE = 44
    }

    private var outputStream: FileOutputStream? = null
    private var totalSamplesWritten = 0
    private var isOpen = false

    private val sampleRate = AudioDataHandler.SAMPLE_RATE
    private val numChannels = AudioDataHandler.NUM_CHANNELS
    private val bitsPerSample = AudioDataHandler.BITS_PER_SAMPLE

    /**
     * Open the file and write a placeholder header.
     */
    fun open(): Boolean {
        return try {
            outputStream = FileOutputStream(file)
            // Write placeholder header (will be patched later)
            val placeholderHeader = ByteArray(WAV_HEADER_SIZE)
            outputStream?.write(placeholderHeader)
            isOpen = true
            totalSamplesWritten = 0
            Log.i(TAG, "Opened streaming WAV file: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open streaming WAV file", e)
            false
        }
    }

    /**
     * Write samples to the file.
     */
    fun writeSamples(samples: ShortArray): Boolean {
        if (!isOpen || outputStream == null) {
            Log.e(TAG, "File not open")
            return false
        }

        return try {
            val buffer = ByteBuffer.allocate(samples.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                buffer.putShort(sample)
            }
            outputStream?.write(buffer.array())
            totalSamplesWritten += samples.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write samples", e)
            false
        }
    }

    /**
     * Close the file and patch the header with correct sizes.
     */
    fun close(): Boolean {
        if (!isOpen) {
            return false
        }

        return try {
            outputStream?.close()
            outputStream = null

            // Patch the header
            patchHeader()

            isOpen = false
            Log.i(TAG, "Closed streaming WAV file, wrote $totalSamplesWritten samples")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close streaming WAV file", e)
            false
        }
    }

    /**
     * Patch the WAV header with the correct file size and data size.
     */
    private fun patchHeader() {
        val dataSize = totalSamplesWritten * 2 // 2 bytes per sample
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val fileSize = dataSize + WAV_HEADER_SIZE - 8

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        header.put("RIFF".toByteArray())
        header.putInt(fileSize)
        header.put("WAVE".toByteArray())

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        // Write the header at the beginning of the file
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    fun getTotalSamplesWritten(): Int = totalSamplesWritten
}
