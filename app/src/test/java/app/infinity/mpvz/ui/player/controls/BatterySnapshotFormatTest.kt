/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class BatterySnapshotFormatTest {
  @Test fun microampReportMatchesThePlatformContract() {
    // BATTERY_PROPERTY_CURRENT_NOW is documented in microamps: -682_000 uA is -682 mA.
    assertEquals(-682f, batteryCurrentMilliAmps(-682_000L), 0.001f)
    assertEquals(1_500f, batteryCurrentMilliAmps(1_500_000L), 0.001f)
  }

  @Test fun milliampReportingOemIsNotDividedTwice() {
    // Samsung/Xiaomi report milliamps; the old /1000 turned 682 mA into 0.7 mA.
    assertEquals(-682f, batteryCurrentMilliAmps(-682L), 0.001f)
    assertEquals(412f, batteryCurrentMilliAmps(412L), 0.001f)
  }

  @Test fun signIsKeptSoDischargeNeverReadsAsCharge() {
    assertEquals(true, batteryCurrentMilliAmps(-682_000L) < 0f)
    assertEquals(true, batteryCurrentMilliAmps(682_000L) > 0f)
    assertEquals(0f, batteryCurrentMilliAmps(0L), 0.001f)
  }
}
