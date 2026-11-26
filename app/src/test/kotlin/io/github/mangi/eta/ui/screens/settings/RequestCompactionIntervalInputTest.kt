package io.github.mangi.eta.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestCompactionIntervalInputTest {
    @Test
    fun acceptsPositiveIntegerRangeIncludingDefaultAndMaximum() {
        listOf(1, 2, 95, 1000, Int.MAX_VALUE).forEach { value ->
            assertEquals(value, parseRequestCompactionIntervalInput(value.toString()))
        }
    }

    @Test
    fun acceptsLeadingZerosWithoutChangingTheValue() {
        assertEquals(95, parseRequestCompactionIntervalInput("00095"))
        assertEquals(Int.MAX_VALUE, parseRequestCompactionIntervalInput("0002147483647"))
    }

    @Test
    fun rejectsEmptyBlankZeroAndNegativeValues() {
        listOf("", " ", "\t", "\n", "0", "0000", "-1", "-95", Int.MIN_VALUE.toString()).forEach { input ->
            assertNull("Should reject <$input>", parseRequestCompactionIntervalInput(input))
        }
    }

    @Test
    fun rejectsOverflowInsteadOfClampingOrWrapping() {
        listOf("2147483648", "4294967391", Long.MAX_VALUE.toString(), "9".repeat(1000)).forEach { input ->
            assertNull("Should reject <$input>", parseRequestCompactionIntervalInput(input))
        }
    }

    @Test
    fun rejectsInvalidCharactersInsteadOfFilteringThemIntoAValidNumber() {
        listOf(
            "abc", "95abc", "+95", "9.5", "9,500", "1e2", "0x5f",
            " 95", "95 ", "9 5", "95\n", "9\t5", "９５", "٩٥", "九十五",
        ).forEach { input ->
            assertNull("Should reject <$input>", parseRequestCompactionIntervalInput(input))
        }
    }
}
