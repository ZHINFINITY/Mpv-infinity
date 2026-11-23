/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Decodes a PGS (HDMV Presentation Graphic Stream, .sup) subtitle file into timed text cues by
 * rendering each display set's bitmap and running on-device OCR over it. This makes image-based
 * embedded tracks translatable, which ffmpeg alone cannot do (it cannot convert bitmaps to text).
 */
object PgsSupDecoder {
  data class DecodedCue(val startMs: Long, val endMs: Long, val text: String)

  private const val SEGMENT_PDS = 0x14 // palette definition
  private const val SEGMENT_ODS = 0x15 // object definition
  private const val SEGMENT_PCS = 0x16 // presentation composition
  private const val SEGMENT_END = 0x80

  /** Parses a .sup file (or an MKV with a PGS track, found by signature scan) into text cues. */
  fun decodeToCues(supFile: File): List<DecodedCue> {
    val bytes = supFile.readBytes()
    val palettes = HashMap<Int, IntArray>() // paletteId -> 256 ARGB entries
    val objects = HashMap<Int, PendingObject>()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val cues = ArrayList<DecodedCue>()
    var pcsPts = -1L
    var pcsObjects: List<ComposeObject> = emptyList()

    var i = 0
    while (i + 13 <= bytes.size) {
      if (bytes[i] != 'P'.code.toByte() || bytes[i + 1] != 'G'.code.toByte() ||
        !isValidSegmentType(bytes[i + 10].toInt() and 0xFF) ||
        readU16(bytes, i + 11) > bytes.size - i - 13
      ) {
        i++
        continue
      }
      val pts = readU32(bytes, i + 2)
      val type = bytes[i + 10].toInt() and 0xFF
      val len = readU16(bytes, i + 11)
      val dataStart = i + 13
      val dataEnd = (dataStart + len).coerceAtMost(bytes.size)
      when (type) {
        SEGMENT_PDS -> parsePds(bytes, dataStart, dataEnd, palettes)
        SEGMENT_ODS -> parseOds(bytes, dataStart, dataEnd, objects)
        SEGMENT_PCS -> {
          // A new PCS ends the previous display set: emit it with this PTS as its end.
          if (pcsPts >= 0) emit(cues, recognizer, palettes, objects, pcsPts, pts, pcsObjects)
          pcsPts = pts
          pcsObjects = parsePcsObjects(bytes, dataStart, dataEnd)
        }
        SEGMENT_END -> {
          if (pcsPts >= 0) {
            emit(cues, recognizer, palettes, objects, pcsPts, -1L, pcsObjects)
          }
          pcsPts = -1L
          pcsObjects = emptyList()
        }
      }
      i = dataEnd
    }
    recognizer.close()
    return cues.filter { it.text.isNotBlank() }
  }

  private fun isValidSegmentType(type: Int): Boolean =
    type == SEGMENT_PDS || type == SEGMENT_ODS || type == SEGMENT_PCS || type == SEGMENT_END

  private class PendingObject {
    var width = 0
    var height = 0
    val data = ArrayList<Byte>()
  }

  private class ComposeObject(val objectId: Int, val x: Int, val y: Int)

  /** Renders the display set's objects onto one bitmap and OCRs it. */
  private fun emit(
    cues: MutableList<DecodedCue>,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    palettes: Map<Int, IntArray>,
    objects: Map<Int, PendingObject>,
    startPts: Long,
    endPts: Long,
    composition: List<ComposeObject>,
  ) {
    if (composition.isEmpty()) return
    var canvasW = 0
    var canvasH = 0
    for (c in composition) {
      val obj = objects[c.objectId] ?: return
      canvasW = maxOf(canvasW, c.x + obj.width)
      canvasH = maxOf(canvasH, c.y + obj.height)
    }
    if (canvasW <= 0 || canvasH <= 0 || canvasW > 4096 || canvasH > 4096) return
    val bitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.BLACK)
    val paint = Paint()
    for (c in composition) {
      val obj = objects[c.objectId] ?: continue
      val decoded = decodeRle(obj, palettes) ?: continue
      canvas.drawBitmap(decoded, c.x.toFloat(), c.y.toFloat(), paint)
    }
    val image = InputImage.fromBitmap(bitmap, 0)
    val text = Tasks.await(recognizer.process(image)).text
    bitmap.recycle()
    if (text.isBlank()) return
    val startMs = startPts / 90
    val endMs = if (endPts > startPts) endPts / 90 else startMs + 5000
    cues.add(DecodedCue(startMs, endMs, text.trim()))
  }

  /** PDS: 5-byte entries (paletteId 1, then Y, Cr, Cb, transparency). */
  private fun parsePds(bytes: ByteArray, start: Int, end: Int, palettes: MutableMap<Int, IntArray>) {
    if (start + 1 > end) return
    val paletteId = bytes[start].toInt() and 0xFF
    val entries = IntArray(256) { Color.TRANSPARENT }
    var p = start + 1
    while (p + 5 <= end) {
      val index = bytes[p].toInt() and 0xFF
      val y = bytes[p + 1].toInt() and 0xFF
      val cr = bytes[p + 2].toInt() and 0xFF
      val cb = bytes[p + 3].toInt() and 0xFF
      val t = bytes[p + 4].toInt() and 0xFF
      entries[index] = yCbCrToArgb(y, cb, cr, t)
      p += 5
    }
    palettes[paletteId] = entries
  }

  /** ODS: object id, version, sequence flags, and (on the first fragment) width/height. */
  private fun parseOds(bytes: ByteArray, start: Int, end: Int, objects: MutableMap<Int, PendingObject>) {
    if (start + 4 > end) return
    val objectId = readU16(bytes, start)
    val flags = bytes[start + 3].toInt() and 0xFF
    var p = start + 4
    val first = flags and 0x80 != 0
    val obj = if (first) {
      if (p + 4 > end) return
      val o = PendingObject()
      o.width = readU16(bytes, p)
      o.height = readU16(bytes, p + 2)
      p += 4
      objects[objectId] = o
      o
    } else {
      objects[objectId] ?: return
    }
    if (p < end) obj.data.addAll(bytes.copyOfRange(p, end).toList())
  }

  /** PCS composition objects: window id + object id + 2D offsets, after the 11-byte header. */
  private fun parsePcsObjects(bytes: ByteArray, start: Int, end: Int): List<ComposeObject> {
    val result = ArrayList<ComposeObject>()
    var p = start + 11
    while (p + 7 <= end) {
      result.add(ComposeObject(readU16(bytes, p + 1), readU16(bytes, p + 3), readU16(bytes, p + 5)))
      p += 7
    }
    return result
  }

  /**
   * PGS run-length decoding (HDMV spec): raw pixel, end-of-line, and 3 run forms
   * (color-0 short/long, color-C short/long).
   */
  private fun decodeRle(obj: PendingObject, palettes: Map<Int, IntArray>): Bitmap? {
    if (obj.width <= 0 || obj.height <= 0 || palettes.isEmpty()) return null
    val palette = palettes.values.first()
    val data = obj.data.toByteArray()
    val bitmap = Bitmap.createBitmap(obj.width, obj.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(obj.width * obj.height)
    var idx = 0
    var line = 0
    var x = 0
    while (idx < data.size && line < obj.height) {
      val b0 = data[idx].toInt() and 0xFF
      var run = 0
      var color = 0
      var advance = 1
      if (b0 != 0) {
        color = b0
        run = 1
      } else {
        if (idx + 1 >= data.size) break
        val b1 = data[idx + 1].toInt() and 0xFF
        when {
          b1 == 0 -> { // end of line
            advance = 2
            x = 0
            line++
            continue
          }
          b1 < 0x40 -> { run = b1; color = 0; advance = 2 }
          b1 < 0x80 -> {
            if (idx + 2 >= data.size) break
            run = ((b1 - 0x40) shl 8) or (data[idx + 2].toInt() and 0xFF)
            color = 0
            advance = 3
          }
          b1 < 0xC0 -> {
            if (idx + 2 >= data.size) break
            run = b1 - 0x80
            color = data[idx + 2].toInt() and 0xFF
            advance = 3
          }
          else -> {
            if (idx + 3 >= data.size) break
            run = ((b1 - 0xC0) shl 8) or (data[idx + 2].toInt() and 0xFF)
            color = data[idx + 3].toInt() and 0xFF
            advance = 4
          }
        }
      }
      val argb = if (color < palette.size) palette[color] else Color.TRANSPARENT
      var i = 0
      while (i < run && x < obj.width) {
        pixels[line * obj.width + x] = argb
        x++
        i++
      }
      idx += advance
    }
    bitmap.setPixels(pixels, 0, obj.width, 0, 0, obj.width, obj.height)
    return bitmap
  }

  private fun yCbCrToArgb(y: Int, cb: Int, cr: Int, transparency: Int): Int {
    if (transparency >= 255) return Color.TRANSPARENT
    val c = y.toDouble()
    val r = (c + 1.4075 * (cr - 128)).toInt().coerceIn(0, 255)
    val g = (c - 0.3455 * (cb - 128) - 0.7169 * (cr - 128)).toInt().coerceIn(0, 255)
    val b = (c + 1.779 * (cb - 128)).toInt().coerceIn(0, 255)
    val a = 255 - transparency
    return Color.argb(a, r, g, b)
  }

  private fun readU16(bytes: ByteArray, off: Int): Int =
    ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

  private fun readU32(bytes: ByteArray, off: Int): Long =
    ((bytes[off].toLong() and 0xFF) shl 24) or
      ((bytes[off + 1].toLong() and 0xFF) shl 16) or
      ((bytes[off + 2].toLong() and 0xFF) shl 8) or
      (bytes[off + 3].toLong() and 0xFF)
}
