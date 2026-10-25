/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the LeakCanary fix (build 1902): cue translation never blocks a worker past onCleared(). */
class SubtitleTranslationCancellationContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java").isDirectory }

  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

  @Test
  fun cueTranslationIsScopedCancelledAndRethrowsCancellation() {
    val vm = source("ui/player/PlayerViewModel.kt")
    val start = vm.indexOf("fun translateEmbeddedSubtitleCue(")
    val body = code(vm.substring(start, vm.indexOf("private fun translationMatchesOriginal(", start)))
    assertTrue(body.contains("embeddedCueTranslationJob?.cancel()\n    embeddedCueTranslationJob = viewModelScope.launch(Dispatchers.IO)"))
    assertTrue(body.contains("if (failure is kotlin.coroutines.cancellation.CancellationException) throw failure"))
    assertFalse("no unscoped launches", body.contains("GlobalScope") || body.contains("CoroutineScope("))
    val cleared = code(vm.substring(vm.indexOf("override fun onCleared()")))
    assertTrue(cleared.contains("embeddedCueTranslationJob?.cancel()"))
    // Reset paths cancel instead of just dropping the reference.
    val drops = Regex("""embeddedCueTranslationJob = null""").findAll(vm).count()
    val cancelledDrops = Regex("""embeddedCueTranslationJob\?\.cancel\(\)\n\s*embeddedCueTranslationJob = null""").findAll(vm).count()
    assertTrue(drops > 0 && drops == cancelledDrops)
  }

  @Test
  fun translatorHttpIsCancellationAware() {
    val translator = code(source("repository/ai/EmbeddedSubtitleTranslator.kt"))
    assertFalse(translator.contains(".execute()"))
    assertTrue(translator.contains(".awaitText()"))
    assertTrue(translator.contains("if (failure is CancellationException) throw failure"))
    assertFalse(translator.contains("recoverCatching"))
  }

  @Test
  fun assistantGenerateContentPathsAreCancellationAware() {
    for (client in listOf("OpenAi", "Anthropic", "OpenRouter", "Groq", "Together", "OpenCode")) {
      val text = code(source("repository/ai/${client}Client.kt"))
      val generate = text.substring(text.indexOf("override suspend fun generateContent("))
      val scope = generate.substring(0, generate.indexOf("\n  }\n").takeIf { it > 0 } ?: generate.length)
      assertFalse("$client.generateContent blocks on execute()", scope.contains(".execute()"))
      assertTrue("$client.generateContent uses awaitText()", scope.contains(".awaitText()"))
    }
  }
}
