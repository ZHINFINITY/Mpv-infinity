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

/** Pins the LeakCanary fix (build 1902): translation HTTP never blocks a worker past cancellation. */
class SubtitleTranslationCancellationContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java").isDirectory }

  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

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
