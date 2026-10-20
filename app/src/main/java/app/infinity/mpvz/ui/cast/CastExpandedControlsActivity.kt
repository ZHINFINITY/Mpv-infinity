/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.cast

import android.view.Menu
import app.infinity.mpvz.R
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity

class CastExpandedControlsActivity : ExpandedControllerActivity() {
  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    super.onCreateOptionsMenu(menu)
    menuInflater.inflate(R.menu.cast_expanded_controls, menu)
    CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
    return true
  }
}
