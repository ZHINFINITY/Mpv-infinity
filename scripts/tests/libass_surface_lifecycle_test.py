#!/usr/bin/env python3
"""Guard libass SurfaceTexture teardown against concurrent native rendering."""
from __future__ import annotations

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/LibassSubtitleSurfaceView.java").read_text(encoding="utf-8")
ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text(encoding="utf-8")
RENDERER = (ROOT / "subtitle-libass/src/main/java/androidx/media3/subtitle/libass/LibassSubtitleRenderer.java").read_text(encoding="utf-8")
JNI = (ROOT / "subtitle-libass/src/main/cpp/libass_jni.cpp").read_text(encoding="utf-8")


def body(source: str, start: str, end: str) -> str:
    return source.split(start, 1)[1].split(end, 1)[0]


class LibassSurfaceLifecycleTest(unittest.TestCase):
    def test_texture_destruction_stops_rendering_before_detaching_and_releasing(self):
        destroyed = body(VIEW, "onSurfaceTextureDestroyed(", "onSurfaceTextureUpdated(")
        self.assertLess(destroyed.index("surfaceReady = false"), destroyed.index("stopRenderLoop()"))
        self.assertLess(destroyed.index("stopRenderLoop()"), destroyed.index("detachNativeSurface()"))
        self.assertLess(destroyed.index("detachNativeSurface()"), destroyed.index("nativeSurface.release()"))

    def test_stop_waits_for_in_flight_native_render_and_engine_detaches_first(self):
        stop = body(VIEW, "private void stopRenderLoop()", "private void detachNativeSurface()")
        self.assertIn("executor.shutdownNow()", stop)
        self.assertIn("executor.awaitTermination(2, TimeUnit.SECONDS)", stop)
        self.assertIn("subtitleOverlay?.setRenderer(null)\n    libassRenderer?.close()", ENGINE)

    def test_java_and_native_renderer_release_are_synchronized(self):
        self.assertIn("public synchronized boolean renderSurface(long positionUs)", RENDERER)
        self.assertIn("public synchronized void close()", RENDERER)
        release = body(JNI, "Java_androidx_media3_subtitle_libass_LibassNative_nativeRelease(", "delete state;")
        self.assertIn("std::lock_guard lock(state->mutex)", release)
        self.assertIn("ANativeWindow_release(state->window)", release)
        self.assertIn("state->window = nullptr", release)


if __name__ == "__main__":
    unittest.main()
