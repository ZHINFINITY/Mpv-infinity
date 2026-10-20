/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowDiagnosticsCaptureTest {
  private fun tempDirectory(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile()

  private fun tempFile(prefix: String): File = File.createTempFile(prefix, ".tmp")

  @Test
  fun archiveContainsOnlyCommittedFilesAndPreservesRelativeNames() {
    val root = tempDirectory("flow-capture-")
    val zip = File(root.parentFile, "${root.name}.zip")
    try {
      File(root, "pair_00").mkdirs()
      File(root, "manifest.json").writeText("{\"status\":\"complete\"}")
      File(root, FlowDiagnosticsTimingWriter.FILE_NAME).writeText("{\"engine\":\"mpv\"}\n")
      File(root, "pair_00/source0.png").writeBytes(byteArrayOf(1, 2, 3, 4))
      File(root, "pair_00/source0.png.part").writeBytes(byteArrayOf(9, 9))

      FlowDiagnosticsArchiveWriter.write(root, zip)

      ZipFile(zip).use { archive ->
        assertEquals("{\"status\":\"complete\"}", archive.getInputStream(archive.getEntry("manifest.json")).bufferedReader().readText())
        assertEquals(
          "{\"engine\":\"mpv\"}\n",
          archive.getInputStream(archive.getEntry(FlowDiagnosticsTimingWriter.FILE_NAME)).bufferedReader().readText(),
        )
        assertEquals(
          listOf<Byte>(1, 2, 3, 4),
          archive.getInputStream(archive.getEntry("pair_00/source0.png")).use { it.readBytes().toList() },
        )
        assertFalse(archive.entries().asSequence().any { it.name.endsWith(".part") })
      }
    } finally {
      root.deleteRecursively()
      zip.delete()
    }
  }

  @Test
  fun archiveWriterRejectsInputsAboveTheBoundedArchiveLimit() {
    val root = tempDirectory("flow-capture-large-")
    val zip = File(root.parentFile, "${root.name}.zip")
    try {
      val tooLarge = File(root, "oversize.bin")
      tooLarge.parentFile?.mkdirs()
      tooLarge.outputStream().use { it.write(0) }
      java.io.RandomAccessFile(tooLarge, "rw").use { it.setLength(68L * 1024 * 1024 + 1) }

      val failure = assertThrows(IllegalArgumentException::class.java) {
        FlowDiagnosticsArchiveWriter.write(root, zip)
      }
      assertTrue(failure.message.orEmpty().contains("capture_archive_too_large"))
    } finally {
      root.deleteRecursively()
      zip.delete()
    }
  }

  @Test
  fun archiveWriterRejectsSymlinkEscapes() {
    val root = tempDirectory("flow-capture-links-")
    val outside = tempFile("flow-capture-outside-")
    val zip = File(root.parentFile, "${root.name}.zip")
    try {
      java.nio.file.Files.createSymbolicLink(File(root, "outside.txt").toPath(), outside.toPath())

      val failure = assertThrows(IllegalArgumentException::class.java) {
        FlowDiagnosticsArchiveWriter.write(root, zip)
      }
      assertTrue(failure.message.orEmpty().contains("capture_path_escape"))
    } finally {
      root.deleteRecursively()
      outside.delete()
      zip.delete()
    }
  }

  @Test
  fun timingWriterDoesNotPrecreateTheNativeExclusiveSessionDirectory() {
    val root = tempDirectory("flow-timing-root-")
    val session = File(root, "flow_session")
    try {
      assertFalse(
        FlowDiagnosticsTimingWriter.append(
          session,
          "mpv",
          "start",
          1L,
          "{\"successful_swap_metric_semantics\":\"not scanout\"}",
        ),
      )
      assertFalse(session.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun timingWriterCapsSnapshotsAndPreservesRateSemantics() {
    val root = tempDirectory("flow-timing-session-")
    try {
      repeat(40) { index ->
        FlowDiagnosticsTimingWriter.append(
          root,
          "native-media3",
          "sample",
          index.toLong(),
          "{\"successful_egl_swap_return_fps\":59.8,\"actual_displayed_fps_available\":false," +
            "\"rate_semantics\":\"eglSwapBuffers return cadence; not SurfaceFlinger scanout\"}",
        )
      }
      val lines = File(root, FlowDiagnosticsTimingWriter.FILE_NAME).readLines()
      assertEquals(32, lines.size)
      assertTrue(lines.all { it.contains("not SurfaceFlinger scanout") })
      assertTrue(lines.all { it.contains("actual_displayed_fps_available") })
      assertTrue(File(root, FlowDiagnosticsTimingWriter.FILE_NAME).length() < 1024 * 1024)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun timingWriterRejectsAPathThatAliasesTheNativeManifest() {
    val root = tempDirectory("flow-timing-alias-")
    val manifest = File(root, "manifest.json")
    val sidecar = File(root, FlowDiagnosticsTimingWriter.FILE_NAME)
    try {
      manifest.writeText("{\"status\":\"capturing\"}")
      java.nio.file.Files.createSymbolicLink(sidecar.toPath(), manifest.toPath())
      assertFalse(
        FlowDiagnosticsTimingWriter.append(
          root,
          "mpv",
          "sample",
          1L,
          "{\"actual_displayed_fps_available\":false}",
        ),
      )
      assertEquals("{\"status\":\"capturing\"}", manifest.readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun unresolvedSessionBlocksASecondCaptureAfterAnErrorState() {
    assertFalse(
      canStartFlowDiagnosticsCapture(
        FlowDiagnosticsCaptureUiState(phase = FlowDiagnosticsCapturePhase.FAILED),
        "flow_unresolved",
      ),
    )
    assertTrue(
      canStartFlowDiagnosticsCapture(
        FlowDiagnosticsCaptureUiState(phase = FlowDiagnosticsCapturePhase.FAILED),
        null,
      ),
    )
  }
}
