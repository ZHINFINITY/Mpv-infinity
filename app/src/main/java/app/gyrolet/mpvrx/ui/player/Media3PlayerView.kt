package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.media3.ui.PlayerView

/** A Media3 video surface with mpvRx's black, control-free presentation defaults. */
class Media3PlayerView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : PlayerView(context, attrs) {
  init {
    useController = false
    setShutterBackgroundColor(Color.BLACK)
    setBackgroundColor(Color.BLACK)
  }
}
