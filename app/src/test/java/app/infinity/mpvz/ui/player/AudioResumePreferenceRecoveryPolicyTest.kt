/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.IOException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResumePreferenceRecoveryPolicyTest {
  @Test
  fun recognizesAndroidKeystoreAuthenticationFailureThroughWrappedCause() {
    val error = IllegalStateException("encrypted preferences could not be opened", AEADBadTagException("bad tag"))

    assertTrue(AudioPlaybackResumeStore.isEncryptedPreferencesAuthenticationFailure(error))
  }

  @Test
  fun doesNotResetEncryptedPreferencesForAnUnrelatedReadFailure() {
    assertFalse(AudioPlaybackResumeStore.isEncryptedPreferencesAuthenticationFailure(IOException("disk unavailable")))
  }
}
