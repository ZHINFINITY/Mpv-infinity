package app.infinity.mpvz.ui.player

/**
 * Coalesces seek requests while guaranteeing that a sustained drag dispatches at least once per
 * [intervalMs]. The first request after reset (or after a dispatch interval) is immediate.
 */
internal class NativeSeekDispatchPolicy(
  private val intervalMs: Long = 120L,
) {
  private var lastDispatchAtMs: Long? = null
  private var pendingTargetMs: Long? = null

  val pendingTarget: Long?
    get() = pendingTargetMs

  fun request(targetMs: Long, nowMs: Long): Long? {
    pendingTargetMs = targetMs.coerceAtLeast(0L)
    val last = lastDispatchAtMs
    return if (last == null || nowMs - last >= intervalMs) {
      pendingTargetMs.also {
        pendingTargetMs = null
        lastDispatchAtMs = nowMs
      }
    } else {
      null
    }
  }

  fun delayUntilDispatch(nowMs: Long): Long {
    val last = lastDispatchAtMs ?: return 0L
    return (intervalMs - (nowMs - last)).coerceAtLeast(1L)
  }

  fun dispatch(nowMs: Long): Long? {
    val target = pendingTargetMs ?: return null
    pendingTargetMs = null
    lastDispatchAtMs = nowMs
    return target
  }

  fun reset() {
    lastDispatchAtMs = null
    pendingTargetMs = null
  }
}
