package app.infinity.mpvz.ui.utils

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Shared back handling for screens that own nested browser state. */
@Composable
fun NavigationBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
  BackHandler(enabled = enabled, onBack = onBack)
}

/** Shared pager wrapper for browser tabs. */
@Composable
fun NavigationPager(
  state: PagerState,
  modifier: Modifier = Modifier,
  beyondViewportPageCount: Int = 1,
  userScrollEnabled: Boolean = true,
  key: ((Int) -> Any)? = null,
  content: @Composable PagerScope.(Int) -> Unit,
) {
  HorizontalPager(
    state = state,
    modifier = modifier,
    beyondViewportPageCount = beyondViewportPageCount,
    userScrollEnabled = userScrollEnabled,
    key = key,
  ) { page ->
    Box(Modifier.fillMaxSize()) { content(page) }
  }
}

/** Returns a cancellable tab-navigation callback shared by music sub-tabs. */
@Composable
fun rememberTabNavigation(state: PagerState): (Int) -> Unit {
  val scope = rememberCoroutineScope()
  return { page ->
    if (page in 0 until state.pageCount && state.currentPage != page) {
      scope.launch { state.animateScrollToPage(page) }
    }
  }
}
