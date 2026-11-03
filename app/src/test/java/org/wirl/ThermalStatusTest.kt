package org.wirl

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalStatusTest {
    @Test fun theStatusCodesHaveNames() {
        assertEquals("none", ThermalStatus.label(0))
        assertEquals("moderate", ThermalStatus.label(2))
        assertEquals("severe", ThermalStatus.label(3))
        assertEquals("shutdown", ThermalStatus.label(6))
    }

    @Test fun anUnknownCodeIsSaidSo() {
        assertEquals("unknown", ThermalStatus.label(42))
    }
}
