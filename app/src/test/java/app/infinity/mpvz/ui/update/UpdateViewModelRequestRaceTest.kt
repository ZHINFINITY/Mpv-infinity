package app.infinity.mpvz.ui.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.infinity.mpvz.domain.update.AppUpdateChannel
import app.infinity.mpvz.domain.update.Release
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateViewModelRequestRaceTest {
  private lateinit var mainDispatcher: TestDispatcher

  @Before fun setMainDispatcher() {
    mainDispatcher = StandardTestDispatcher()
    Dispatchers.setMain(mainDispatcher)
  }

  @After fun resetMainDispatcher() {
    Dispatchers.resetMain()
  }

  @Test fun manualCheckBeforeStartupDelayCannotBeReplacedByDelayedAutomaticCheck() =
    runTest(mainDispatcher) {
      val requests = mutableListOf<Boolean>()
      val manualResult = CompletableDeferred<Release?>()
      val viewModel = newViewModel(autoUpdate = true) { _, manual ->
        requests += manual
        manualResult.await()
      }

      runCurrent()
      advanceTimeBy(UPDATE_CHECK_STARTUP_DELAY_MS / 2)
      viewModel.checkForUpdate(manual = true)
      runCurrent()
      advanceTimeBy(UPDATE_CHECK_STARTUP_DELAY_MS)
      runCurrent()

      assertEquals(listOf(true), requests)
      assertTrue(viewModel.isManualUpdateCheck.value)
      manualResult.complete(null)
      runCurrent()
      assertEquals(UpdateViewModel.UpdateState.NoUpdate, viewModel.updateState.value)
    }

  @Test fun disablingAutoUpdateCancelsThePendingStartupCheck() = runTest(mainDispatcher) {
    val requests = mutableListOf<Boolean>()
    val viewModel = newViewModel(autoUpdate = true) { _, manual ->
      requests += manual
      null
    }

    runCurrent()
    advanceTimeBy(UPDATE_CHECK_STARTUP_DELAY_MS / 2)
    viewModel.toggleAutoUpdate(enabled = false)
    advanceTimeBy(UPDATE_CHECK_STARTUP_DELAY_MS)
    runCurrent()

    assertTrue(requests.isEmpty())
  }

  @Test fun enablingAutoUpdateCannotReplaceAnActiveManualCheckButCanRunAfterItFinishes() =
    runTest(mainDispatcher) {
      val requests = mutableListOf<Boolean>()
      val manualResult = CompletableDeferred<Release?>()
      val viewModel = newViewModel(autoUpdate = false) { _, manual ->
        requests += manual
        if (manual) manualResult.await() else null
      }

      viewModel.checkForUpdate(manual = true)
      runCurrent()
      viewModel.toggleAutoUpdate(enabled = true)
      runCurrent()

      assertEquals(listOf(true), requests)
      assertTrue(viewModel.isManualUpdateCheck.value)
      assertEquals(UpdateViewModel.UpdateState.Loading, viewModel.updateState.value)

      manualResult.complete(null)
      runCurrent()
      assertEquals(UpdateViewModel.UpdateState.NoUpdate, viewModel.updateState.value)

      viewModel.toggleAutoUpdate(enabled = true)
      runCurrent()

      assertEquals(listOf(true, false), requests)
      assertFalse(viewModel.isManualUpdateCheck.value)
      assertEquals(UpdateViewModel.UpdateState.Idle, viewModel.updateState.value)
    }

  private fun newViewModel(
    autoUpdate: Boolean,
    updateCheck: suspend (AppUpdateChannel, Boolean) -> Release?,
  ): UpdateViewModel {
    val application = ApplicationProvider.getApplicationContext<Application>()
    application
      .getSharedPreferences("Mpv∞_prefs", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .putBoolean("auto_update", autoUpdate)
      .commit()
    return UpdateViewModel(application, updateCheck)
  }

  private companion object {
    const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
  }
}
