package app.infinity.mpvz.ui.player

import android.net.Uri
import android.util.Log
import java.io.RandomAccessFile

/** Reads Matroska AttachedFile elements without changing Media3's extractor or video pipeline. */
object MatroskaFontScanner {
  data class Font(val name: String, val bytes: ByteArray)
  private const val TAG = "MatroskaFontScanner"
  private val MIME = setOf("application/x-truetype-font", "font/otf", "font/ttf", "application/vnd.ms-opentype")

  fun scan(uri: Uri): List<Font> {
    if (uri.scheme != "file") return emptyList()
    return runCatching {
      RandomAccessFile(uri.path!!, "r").use { file ->
        val result = ArrayList<Font>()
        walk(file, 0L, file.length(), 0, result)
        Log.i(TAG, "scan_complete uri=$uri fonts=${result.size}")
        result
      }
    }.onFailure { Log.w(TAG, "scan_failed uri=$uri", it) }.getOrDefault(emptyList())
  }

  private fun walk(file: RandomAccessFile, start: Long, end: Long, depth: Int, out: MutableList<Font>) {
    if (depth > 12 || start >= end) return
    file.seek(start)
    while (file.filePointer < end) {
      val id = readId(file) ?: return
      val size = readSize(file) ?: return
      val dataStart = file.filePointer
      val dataEnd = (dataStart + size).coerceAtMost(end)
      if (id == 0x61A7L) readAttached(file, dataStart, dataEnd, out)
      else if (isMaster(id)) walk(file, dataStart, dataEnd, depth + 1, out)
      file.seek(dataEnd)
    }
  }

  private fun readAttached(file: RandomAccessFile, start: Long, end: Long, out: MutableList<Font>) {
    var name = "embedded-font-${out.size}"
    var mime = ""
    var data: ByteArray? = null
    file.seek(start)
    while (file.filePointer < end) {
      val id = readId(file) ?: return
      val size = readSize(file) ?: return
      val dataStart = file.filePointer
      val dataEnd = (dataStart + size).coerceAtMost(end)
      when (id) {
        0x466E -> name = readUtf8(file, size)
        0x4660 -> mime = readUtf8(file, size).lowercase()
        0x465C -> if (size <= Int.MAX_VALUE) { file.seek(dataStart); data = ByteArray(size.toInt()).also { file.readFully(it) } }
      }
      file.seek(dataEnd)
    }
    if (data != null && MIME.contains(mime)) {
      out += Font(name, data!!)
      Log.i(TAG, "font_found name=$name mime=$mime bytes=${data!!.size}")
    }
  }

  private fun readUtf8(file: RandomAccessFile, size: Long): String {
    if (size <= 0 || size > 1_048_576) return ""
    val bytes = ByteArray(size.toInt()); file.readFully(bytes)
    return bytes.toString(Charsets.UTF_8).trim('\u0000')
  }
  private fun isMaster(id: Long) = id == 0x1A45DFA3L || id == 0x18538067L || id == 0x114D9B74L || id == 0x1549A966L || id == 0x1654AEL || id == 0x1941A469L || id == 0x1043A770L || id == 0x1254C367L

  private fun readId(file: RandomAccessFile): Long? {
    val first = file.read(); if (first < 0) return null
    val mask = when { first and 0x80 != 0 -> 0x80; first and 0x40 != 0 -> 0x40; first and 0x20 != 0 -> 0x20; first and 0x10 != 0 -> 0x10; else -> return null }
    val length = Integer.numberOfTrailingZeros(mask) + 1
    var value = first.toLong()
    repeat(length - 1) { val b = file.read(); if (b < 0) throw java.io.EOFException(); value = (value shl 8) or b.toLong() }
    return value
  }
  private fun readSize(file: RandomAccessFile): Long? {
    val first = file.read(); if (first < 0) return null
    val mask = when { first and 0x80 != 0 -> 0x80; first and 0x40 != 0 -> 0x40; first and 0x20 != 0 -> 0x20; first and 0x10 != 0 -> 0x10; first and 0x08 != 0 -> 0x08; first and 0x04 != 0 -> 0x04; first and 0x02 != 0 -> 0x02; else -> return null }
    val length = Integer.numberOfTrailingZeros(mask) + 1
    var value = (first and (mask - 1)).toLong()
    repeat(length - 1) { val b = file.read(); if (b < 0) throw java.io.EOFException(); value = (value shl 8) or b.toLong() }
    return if (value == ((1L shl (7 * length)) - 1L)) file.length() - file.filePointer else value
  }
}
