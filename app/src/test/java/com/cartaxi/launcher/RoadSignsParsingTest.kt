package com.cartaxi.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Lightweight regression tests for free OSM tags; no network or API key needed. */
class RoadSignsParsingTest {
    private fun engine(): RoadSigns =
        RoadSigns(File.createTempFile("road-signs-test", ".cache").apply { delete() })

    @Test fun parseSpeedHandlesKmhMphAndInvalidValues() {
        val target = engine()
        val parse = RoadSigns::class.java.getDeclaredMethod("parseSpeed", String::class.java)
        parse.isAccessible = true
        assertEquals(60, parse.invoke(target, "60"))
        assertEquals(80, parse.invoke(target, "80 km/h"))
        assertEquals(80, parse.invoke(target, "50 mph"))
        assertNull(parse.invoke(target, "signals"))
        assertNull(parse.invoke(target, "50;80"))
        assertNull(parse.invoke(target, ""))
    }

    @Test fun signFacingParsesNumericAndCompassValues() {
        val target = engine()
        val parse = RoadSigns::class.java.getDeclaredMethod("parseFacing", String::class.java)
        parse.isAccessible = true
        assertEquals(180.0, parse.invoke(target, "S") as Double, 0.001)
        assertEquals(90.0, parse.invoke(target, "E") as Double, 0.001)
        assertEquals(270.0, parse.invoke(target, "-90") as Double, 0.001)
        assertNull(parse.invoke(target, "not-a-bearing"))
    }
}
