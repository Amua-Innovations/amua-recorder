package com.amua.audiodownloader.audio

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles parsing of incoming audio data packets from BLE.
 *
 * Packet format (based on record.py):
 * - Bytes 0-1: Sequence number (uint16, little-endian)
 * - Bytes 1-242: 121 audio samples (int16, little-endian)
 *
 * Note: The Python script uses bytes[1:243] which suggests there might be
 * an offset. We'll handle both formats by detecting packet structure.
 */
class AudioDataHandler {

    companion object {
        private const val TAG = "AudioDataHandler"
        const val SAMPLE_RATE = 32000
        const val BITS_PER_SAMPLE = 16
        const val NUM_CHANNELS = 1

        // Expected samples per packet based on record.py
        private const val SAMPLES_PER_PACKET = 121
        private const val BYTES_PER_SAMPLE = 2
    }

    private val audioSamples = mutableListOf<Short>()
    private var packetCount = 0
    private var lastSequenceNumber: Int = -1

    /**
     * Process an incoming audio data packet.
     * Returns the number of samples extracted.
     */
    fun processPacket(data: ByteArray): Int {
        if (data.size < 4) {
            Log.w(TAG, "Packet too small: ${data.size} bytes")
            return 0
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Read sequence number from first 2 bytes
        val sequenceNumber = buffer.short.toInt() and 0xFFFF

        // Check for sequence gaps
        if (lastSequenceNumber >= 0 && sequenceNumber != (lastSequenceNumber + 1) and 0xFFFF) {
            val gap = if (sequenceNumber > lastSequenceNumber) {
                sequenceNumber - lastSequenceNumber - 1
            } else {
                // Handle wraparound
                (0xFFFF - lastSequenceNumber) + sequenceNumber
            }
            if (gap > 0) {
                Log.w(TAG, "Sequence gap detected: $gap packets missing (last: $lastSequenceNumber, current: $sequenceNumber)")
            }
        }
        lastSequenceNumber = sequenceNumber

        // Extract audio samples
        // Based on record.py: samples = struct.unpack('<121h', data[1:243])
        // This means starting at byte 1, reading 121 samples (242 bytes)
        val sampleStartOffset = 1 // Skip first byte (part of seq number handling in Python)
        val availableBytes = data.size - sampleStartOffset
        val numSamples = minOf(SAMPLES_PER_PACKET, availableBytes / BYTES_PER_SAMPLE)

        if (numSamples > 0) {
            val sampleBuffer = ByteBuffer.wrap(data, sampleStartOffset, numSamples * BYTES_PER_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numSamples) {
                audioSamples.add(sampleBuffer.short)
            }
        }

        packetCount++

        if (packetCount % 100 == 0) {
            Log.d(TAG, "Processed $packetCount packets, ${audioSamples.size} total samples")
        }

        return numSamples
    }

    /**
     * Get the current audio samples as a ShortArray.
     */
    fun getSamples(): ShortArray = audioSamples.toShortArray()

    /**
     * Get the current sample count.
     */
    fun getSampleCount(): Int = audioSamples.size

    /**
     * Get the current packet count.
     */
    fun getPacketCount(): Int = packetCount

    /**
     * Get the recording duration in seconds.
     */
    fun getDurationSeconds(): Float = audioSamples.size.toFloat() / SAMPLE_RATE

    /**
     * Clear all stored audio data.
     */
    fun clear() {
        audioSamples.clear()
        packetCount = 0
        lastSequenceNumber = -1
        Log.i(TAG, "Audio data cleared")
    }
}
