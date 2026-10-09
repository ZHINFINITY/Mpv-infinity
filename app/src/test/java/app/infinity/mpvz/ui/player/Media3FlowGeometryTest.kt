package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowGeometryTest {
  @Test
  fun fullHdSourceUsesReducedMotionGrid() {
    assertEquals(Media3FlowMotionSize(480, 270), Media3FlowGeometry.motionSize(1920, 1080, 480))
  }

  @Test
  fun portraitSourceFitsTheSameMotionCapWithoutChangingAspectRatio() {
    assertEquals(Media3FlowMotionSize(270, 480), Media3FlowGeometry.motionSize(1080, 1920, 480))
  }

  @Test
  fun motionGridUsesOnlyValidRegularBlockOriginsAtDivisibleAndNonDivisibleSizes() {
    val nonDivisibleGrid = Media3FlowGeometry.motionGridSize(640, 360)
    assertEquals(Media3FlowGridSize(106, 59), nonDivisibleGrid)
    assertEquals(630, (nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP)
    assertEquals(348, (nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP)
    assertTrue((nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP <= 640 - MEDIA3_FLOW_BLOCK_SIZE)
    assertTrue((nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP <= 360 - MEDIA3_FLOW_BLOCK_SIZE)

    assertEquals(Media3FlowGridSize(105, 60), Media3FlowGeometry.motionGridSize(632, 362))
  }

  @Test
  fun synthesisSamplesMotionAtPatchCentersAndClampsGridEdges() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertEquals(4f, Media3FlowGeometry.motionGridAnchorOffset(), 0f)
    assertTrue(shader.contains("uniform float uGridAnchorOffset;"))
    assertTrue(shader.contains("vec2 gridPos = (p - vec2(uGridAnchorOffset)) / float(uStep);"))
    assertTrue(shader.contains("gridPos = clamp(gridPos, vec2(0.0), vec2(uGrid - ivec2(1)));"))
  }

  @Test
  fun synthesisSubtractsBackwardFlowWhenSamplingFrameOne() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("source1Point = motionPoint - (1.0 - uAlpha) * backwardAtMid.xy;"))
    assertTrue(shader.contains("source1Point = motionPoint - (1.0 - uAlpha) * backwardAtTarget.xy;"))
  }

  @Test
  fun sourcesBelowCapKeepTheirNativeMotionDimensions() {
    assertEquals(Media3FlowMotionSize(320, 180), Media3FlowGeometry.motionSize(320, 180, 480))
  }

  @Test
  fun fitCentersTheWholeSourceInsideTheOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 200,
        viewportY = 0,
        viewportWidth = 1200,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Fit),
    )
  }

  @Test
  fun fitLetterboxesAWideSourceWithCenteredTopAndBottomBars() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 200,
        viewportWidth = 800,
        viewportHeight = 400,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Fit),
    )
  }

  @Test
  fun cropUsesCenteredTextureCoordinatesToFillTheOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 1600,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 0.75f,
        textureOffsetX = 0f,
        textureOffsetY = 0.125f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Crop),
    )
  }

  @Test
  fun cropCentersAHorizontalCutForAWideSource() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 800,
        viewportHeight = 800,
        textureScaleX = 0.5f,
        textureScaleY = 1f,
        textureOffsetX = 0.25f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Crop),
    )
  }

  @Test
  fun stretchUsesTheEntireTextureAndOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 1600,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Stretch),
    )
  }
}
