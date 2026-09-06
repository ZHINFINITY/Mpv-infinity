package app.infinity.mpvz.ui.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A lightweight time-to-byte index built without blocking Native Media3 startup. */
internal data class MkvCuePoint(val timeUs: Long, val clusterPosition: Long)

internal data class MkvCueIndexSnapshot(
  val points: List<MkvCuePoint>,
  val complete: Boolean,
)

internal object MkvCueIndex {
  private const val TAG = "Mpv∞-MkvIndex"
  private const val EBML_ID = 0x1A45DFA3L
  private const val SEGMENT_ID = 0x18538067L
  private const val CUES_ID = 0x1C53BB6BL
  private const val CUE_POINT_ID = 0xBBL
  private const val CUE_TIME_ID = 0xB3L
  private const val CUE_TRACK_POSITIONS_ID = 0xB7L
  private const val CUE_CLUSTER_POSITION_ID = 0xF1L
  private const val MAX_SCAN_BYTES = 32L * 1024L * 1024L
  private const val MAX_POINTS = 200_000

  private val cache = ConcurrentHashMap<String, MkvCueIndexSnapshot>()
  private val jobs = ConcurrentHashMap<String, Job>()

  fun start(scope: CoroutineScope, uri: Uri, onUpdate: (MkvCueIndexSnapshot) -> Unit = {}) {
    val path = uri.path?.takeIf { uri.scheme == "file" } ?: return
    if (cache[path]?.complete == true || jobs[path]?.isActive == true) return
    jobs[path] = scope.launch(Dispatchers.IO) {
      val started = SystemClock.elapsedRealtime()
      val snapshot = runCatching { scan(path) }
        .onFailure { Log.w(TAG, "Cue scan failed for $path", it) }
        .getOrDefault(MkvCueIndexSnapshot(emptyList(), complete = false))
      cache[path] = snapshot
      onUpdate(snapshot)
      Log.d(TAG, "Cue scan complete=${snapshot.complete} points=${snapshot.points.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
      jobs.remove(path)
    }
  }

  fun snapshot(uri: Uri): MkvCueIndexSnapshot? = uri.path?.let(cache::get)

  private fun scan(path: String): MkvCueIndexSnapshot {
    RandomAccessFile(path, "r").use { file ->
      val length = file.length()
      val end = minOf(length, MAX_SCAN_BYTES)
      val points = ArrayList<MkvCuePoint>()
      var offset = 0L
      while (offset + 4 < end && points.size < MAX_POINTS) {
        file.seek(offset)
        val id = readId(file)
        if (id == CUES_ID) {
          val size = readVint(file) ?: break
          val cuesEnd = minOf(file.filePointer + size, length)
          var cueTimeUs: Long? = null
          while (file.filePointer < cuesEnd && points.size < MAX_POINTS) {
            val childId = readId(file)
            val childSize = readVint(file) ?: break
            val childEnd = minOf(file.filePointer + childSize, cuesEnd)
            when (childId) {
              CUE_POINT_ID -> {
                var pointTime: Long? = null
                var cluster: Long? = null
                while (file.filePointer < childEnd) {
                  val pointId = readId(file)
                  val pointSize = readVint(file) ?: break
                  val pointEnd = minOf(file.filePointer + pointSize, childEnd)
                  when (pointId) {
                    CUE_TIME_ID -> pointTime = readUnsigned(file, pointSize)
                    CUE_TRACK_POSITIONS_ID -> {
                      while (file.filePointer < pointEnd) {
                        val positionId = readId(file)
                        val positionSize = readVint(file) ?: break
                        if (positionId == CUE_CLUSTER_POSITION_ID) {
                          cluster = readUnsigned(file, positionSize)
                        } else {
                          file.seek(minOf(file.filePointer + positionSize, pointEnd))
                        }
                      }
                    }
                    else -> file.seek(pointEnd)
                  }
                }
                if (pointTime != null && cluster != null) points += MkvCuePoint(pointTime, cluster)
              }
              else -> file.seek(childEnd)
            }
          }
          return MkvCueIndexSnapshot(points.sortedBy { it.timeUs }, complete = points.isNotEmpty())
        }
        offset += 1
      }
      return MkvCueIndexSnapshot(emptyList(), complete = false)
    }
  }

  private fun readId(file: RandomAccessFile): Long {
    val first = file.readUnsignedByte()
    val mask = highestBit(first)
    val length = mask.takeIf { it > 0 }?.let { Integer.numberOfTrailingZeros(it) + 1 } ?: 1
    var value = first.toLong()
    repeat(length - 1) { value = (value shl 8) or file.readUnsignedByte().toLong() }
    return value
  }

  private fun readVint(file: RandomAccessFile): Long? {
    val first = file.readUnsignedByte()
    val mask = highestBit(first)
    if (mask == 0) return null
    val length = Integer.numberOfTrailingZeros(mask) + 1
    var value = (first and (mask - 1)).toLong()
    repeat(length - 1) { value = (value shl 8) or file.readUnsignedByte().toLong() }
    return value
  }

  private fun readUnsigned(file: RandomAccessFile, size: Long): Long {
    var value = 0L
    repeat(size.toInt().coerceAtMost(8)) { value = (value shl 8) or file.readUnsignedByte().toLong() }
    return value
  }

  private fun highestBit(value: Int): Int = Integer.highestOneBit(value)
}
