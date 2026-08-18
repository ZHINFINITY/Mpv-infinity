/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package app.gyrolet.mpvrx.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Lightweight PCM-only effects for the Native engine.
 *
 * The existing MPV effects are significantly more configurable. This processor deliberately keeps
 * the Native path conservative: it applies a gentle gain lift for normalization and a soft-knee
 * compressor for DRC, while leaving encoded passthrough/offload untouched by Media3.
 */
@UnstableApi
internal class NativeAudioEffectsProcessor : AudioProcessor {
  @Volatile var volumeNormalizationEnabled: Boolean = false
  @Volatile var drcEnabled: Boolean = false

  private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
  private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
  private var outputBuffer = AudioProcessor.EMPTY_BUFFER
  private var inputEnded = false

  override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      pendingFormat = AudioProcessor.AudioFormat.NOT_SET
      return AudioProcessor.AudioFormat.NOT_SET
    }
    pendingFormat = inputAudioFormat
    return inputAudioFormat
  }

  override fun isActive(): Boolean = pendingFormat != AudioProcessor.AudioFormat.NOT_SET

  override fun queueInput(inputBuffer: ByteBuffer) {
    val output = ByteBuffer.allocateDirect(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
    while (inputBuffer.remaining() >= 2) {
      var sample = inputBuffer.order(ByteOrder.nativeOrder()).short.toInt() / 32768f
      if (volumeNormalizationEnabled) sample *= 1.18f
      if (drcEnabled) {
        val magnitude = abs(sample)
        val threshold = 0.55f
        if (magnitude > threshold) {
          sample = sample.sign * (threshold + (magnitude - threshold) * 0.35f)
        }
      }
      val pcm = (sample.coerceIn(-1f, 0.9999695f) * 32767f).roundToInt()
      output.putShort(pcm.toShort())
    }
    output.flip()
    outputBuffer = output
  }

  override fun queueEndOfStream() {
    inputEnded = true
  }

  override fun getOutput(): ByteBuffer {
    val result = outputBuffer
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    return result
  }

  override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

  override fun flush() {
    inputFormat = pendingFormat
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    inputEnded = false
  }

  override fun reset() {
    pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    inputFormat = AudioProcessor.AudioFormat.NOT_SET
    outputBuffer = AudioProcessor.EMPTY_BUFFER
    inputEnded = false
  }
}
