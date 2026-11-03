package com.obdfree.app.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmAaDpfCodecTest {
    @Test fun `decodes real style 5E8 AA0329 frame`() {
        val p = requireNotNull(GmAaDpfCodec.packet29("5E8 29 58 14 05 00 3A 00 00"))
        assertEquals(69.0f, p.sootPercent, 0.1f)
        assertEquals(0f, p.differentialPressureMbar, 0.01f)
        assertEquals(0.392f, p.pressureSensorVoltage, 0.01f)
        assertEquals(58, p.regenerationsSinceReplacement)
    }
    @Test fun `decodes A17DTS AA032A distances and progress`() {
        val p = requireNotNull(GmAaDpfCodec.packet2A("5E8 2A 00 01 2C 00 00 64 80"))
        assertEquals(300, p.distanceSinceReplacementKm)
        assertEquals(100, p.distanceSinceRegenerationKm)
        assertEquals(50.2f, p.regenerationProgressPercent, 0.2f)
    }
    @Test fun `accepts length-prefixed 5E8 frames`() {
        val p = requireNotNull(GmAaDpfCodec.packet2A("5E8 08 2A 00 00 01 00 00 02 00"))
        assertEquals(2, p.distanceSinceRegenerationKm)
    }
    @Test fun `does not infer packet data from negative responses`() {
        assertNull(GmAaDpfCodec.packet29("NO DATA"))
        assertNull(GmAaDpfCodec.packet2A("7E8 03 7F AA 11"))
        assertNull(GmAaDpfCodec.packet2A("5E8 2A 00 01"))
    }
}
