package app.infinity.mpvz.ui.player

/** Main-thread-only live-seek throttle; continuous pointer events never postpone its deadline. */
internal class NativeSeekDispatcher(
  private val nowMs: () -> Long,
  private val postDelayed: (Runnable, Long) -> Unit,
  private val removeCallbacks: (Runnable) -> Unit,
  private val dispatch: (positionMs: Long, committed: Boolean) -> Unit,
  private val throttleMs: Long = 150L,
) {
  private val timer = Runnable { onTimer() }
  private var timerScheduled = false
  private var lastDispatchMs: Long? = null

  /** Only an undispatched target may be used as the base for a subsequent relative seek. */
  var pendingPositionMs: Long? = null
    private set

  fun preview(positionMs: Long) {
    pendingPositionMs = positionMs.coerceAtLeast(0L)
    if (timerScheduled) return
    timerScheduled = true
    val delay = lastDispatchMs?.let { throttleMs - (nowMs() - it) } ?: 0L
    postDelayed(timer, delay.coerceAtLeast(0L))
  }

  fun commit(positionMs: Long) {
    if (timerScheduled) removeCallbacks(timer)
    timerScheduled = false
    pendingPositionMs = null
    lastDispatchMs = nowMs()
    dispatch(positionMs.coerceAtLeast(0L), true)
  }

  fun cancel() {
    if (timerScheduled) removeCallbacks(timer)
    timerScheduled = false
    pendingPositionMs = null
    lastDispatchMs = null
  }

  private fun onTimer() {
    timerScheduled = false
    val target = pendingPositionMs ?: return
    pendingPositionMs = null
    lastDispatchMs = nowMs()
    dispatch(target, false)
  }
}
