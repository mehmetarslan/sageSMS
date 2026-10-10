package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.NotificationExtractionModes.NUMBER
import dev.octoshrimpy.quik.model.NotificationExtractionModes.UNTIL_RIGHT_ANCHOR
import dev.octoshrimpy.quik.model.NotificationExtractionModes.WORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractByAnchorsTest {

    // Rule learned from "Kartinizdan 12312 TL harcama yapildi"
    private fun amount(body: String) = extractByAnchors(body, "Kartinizdan ", " TL harcama", NUMBER)

    @Test
    fun amountOfAnyLength() {
        assertEquals("12312", amount("Kartinizdan 12312 TL harcama yapildi"))
        assertEquals("123", amount("Kartinizdan 123 TL harcama yapildi"))
        assertEquals("7", amount("Kartinizdan 7 TL harcama yapildi"))
    }

    @Test
    fun amountWithSeparators() {
        assertEquals("12.312,50", amount("Kartinizdan 12.312,50 TL harcama yapildi"))
        assertEquals("1,234.99", amount("Kartinizdan 1,234.99 TL harcama yapildi"))
    }

    @Test
    fun spacingAndCaseAroundAnchorsMayDiffer() {
        assertEquals("123", amount("Kartinizdan 123TL harcama yapildi"))
        assertEquals("123", amount("KARTINIZDAN   123 tl harcama yapildi"))
        assertEquals("450", amount("Kartinizdan 450 TL\nharcama yapildi"))
    }

    @Test
    fun amountWithOnlyRightAnchorSkipsEarlierNumbers() {
        assertEquals(
            "500",
            extractByAnchors("Kart 1234 ile 500 TL harcama yapildi", "", "TL harcama", NUMBER)
        )
    }

    @Test
    fun turkishCharactersInAnchorsIgnoreCase() {
        assertEquals(
            "98,40",
            extractByAnchors("FATURA TUTARINIZ 98,40 TL'DİR", "fatura tutarınız", "tl", NUMBER)
        )
        assertEquals(
            "98,40",
            extractByAnchors("Fatura tutarınız 98,40 TL'dir", "FATURA TUTARINIZ", "TL", NUMBER)
        )
    }

    @Test
    fun codeOfAnyLength() {
        val rule = { body: String -> extractByAnchors(body, "kodunuz:", "ile", WORD) }
        assertEquals("4821", rule("Teslimat kodunuz: 4821 ile alabilirsiniz"))
        assertEquals("AB12-CD9", rule("Teslimat kodunuz: AB12-CD9 ile alabilirsiniz"))
    }

    @Test
    fun untilRightAnchorCapturesFreeText() {
        assertEquals(
            "MIGROS KADIKOY",
            extractByAnchors("MIGROS KADIKOY isyerinde 250 TL harcama", "", "isyerinde", UNTIL_RIGHT_ANCHOR)
        )
        assertEquals(
            "Ahmet Yilmaz",
            extractByAnchors("Gonderen: Ahmet Yilmaz\nTutar: 50 TL", "Gonderen:", "", UNTIL_RIGHT_ANCHOR)
        )
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(amount("Kampanya: yeni kart firsatlari"))
        assertNull(amount("Kartinizdan TL harcama yapildi"))
        assertNull(extractByAnchors("anything", "", "", UNTIL_RIGHT_ANCHOR))
    }

    @Test
    fun regexCharactersInAnchorsAreLiteral() {
        assertEquals(
            "42",
            extractByAnchors("Toplam (KDV dahil): 42 TL.", "(KDV dahil):", "TL.", NUMBER)
        )
    }
}
