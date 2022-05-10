package info.benjaminhill.fm.chopper

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class MathHelpersKtTest {
    @Test
    fun findNextPowerOf2Test() {
        Assertions.assertEquals(2, findNextPowerOf2(1))
        Assertions.assertEquals(16, findNextPowerOf2(15))
        Assertions.assertEquals(16, findNextPowerOf2(16))
        Assertions.assertEquals(32, findNextPowerOf2(17))
    }

    @Test
    fun toUnitRangeTest() {
        val inputs = listOf(0.0, 10.0, 5.0).toDoubleArray()
        val expected = listOf(0.0, 1.0, 0.5).toDoubleArray()
        Assertions.assertArrayEquals(expected, inputs.toUnitRange())
    }
}