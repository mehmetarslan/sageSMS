package dev.octoshrimpy.quik.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractByIndexedPatternTest {

    @Test
    fun extractsBetweenBothAnchors() {
        val result = extractByIndexedPattern(
            body = "Teslimat kodunuz: 4821 ile teslim alabilirsiniz",
            searchText = "kodunuz: 1234 ile",
            leftAnchor = "kodunuz: ",
            rightAnchor = " ile",
            extractLength = 4
        )
        assertEquals("4821", result)
    }

    @Test
    fun extractsWithOnlyRightAnchorWhenValueIsMidMessage() {
        val result = extractByIndexedPattern(
            body = "Merhaba, 7391 dogrulama kodunuzdur",
            searchText = "1234 dogrulama",
            leftAnchor = "",
            rightAnchor = " dogrulama",
            extractLength = 4
        )
        assertEquals("7391", result)
    }

    @Test
    fun extractsWithOnlyRightAnchorAtMessageStart() {
        val result = extractByIndexedPattern(
            body = "7391 dogrulama kodunuzdur",
            searchText = "1234 dogrulama",
            leftAnchor = "",
            rightAnchor = " dogrulama",
            extractLength = 4
        )
        assertEquals("7391", result)
    }

    @Test
    fun returnsNullWhenAnchorsDoNotMatch() {
        val result = extractByIndexedPattern(
            body = "Kampanya mesaji",
            searchText = "kodunuz: 1234 ile",
            leftAnchor = "kodunuz: ",
            rightAnchor = " ile",
            extractLength = 4
        )
        assertNull(result)
    }

    @Test
    fun returnsNullWhenLengthsAreInconsistent() {
        val result = extractByIndexedPattern(
            body = "kodunuz: 4821 ile",
            searchText = " kodunuz: 1234 ile",
            leftAnchor = "kodunuz: ",
            rightAnchor = " ile",
            extractLength = 4
        )
        assertNull(result)
    }
}
