package dev.octoshrimpy.quik.rules

import dev.octoshrimpy.quik.rules.NotificationPatternGenerator.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationPatternGeneratorTest {

    /** Builds a rule by "selecting" [selected] (its first occurrence) in [sample]. */
    private fun rule(sample: String, selected: String, examples: List<String> = emptyList()): String {
        val start = sample.indexOf(selected)
        require(start >= 0) { "'$selected' not in sample" }
        val result = NotificationPatternGenerator.generate(sample, start, start + selected.length, examples)
        assertNotNull("No pattern for '$selected' in: $sample", result)
        assertEquals(selected.trim(), result!!.value)
        return result.pattern
    }

    private fun extract(pattern: String, body: String) = NotificationPatternGenerator.extract(pattern, body)

    // --- Card spending -----------------------------------------------------------------------------

    private val spend1 = "12.10.2026 14:32'de 5528****1234 nolu kartınızdan MIGROS KADIKOY işyerinde 12.312,50 TL harcama yapılmıştır. B002"
    private val spend2 = "03.10.2026 09:05'te 5528****1234 nolu kartınızdan SHELL işyerinde 123 TL harcama yapılmıştır. B002"
    private val spend3 = "28.09.2026 21:47'de 5528****1234 nolu kartınızdan ZARA ISTINYE PARK işyerinde 1.250,00 TL harcama yapılmıştır. B002"

    @Test
    fun amountOfAnyLengthFromSingleExample() {
        val pattern = rule(spend1, "12.312,50")
        assertEquals("123", extract(pattern, spend2))
        assertEquals("1.250,00", extract(pattern, spend3))
    }

    @Test
    fun amountWithoutSpaceBeforeCurrency() {
        val pattern = rule(spend1, "12.312,50", listOf(spend2, spend3))
        assertEquals("450", extract(pattern, spend2.replace("123 TL", "450TL")))
    }

    @Test
    fun merchantNameOfAnyWordCountLearnedFromHistory() {
        val pattern = rule(spend1, "MIGROS KADIKOY", listOf(spend2, spend3))
        assertEquals("SHELL", extract(pattern, spend2))
        assertEquals("ZARA ISTINYE PARK", extract(pattern, spend3))
    }

    @Test
    fun merchantNameFromSingleExampleUsesCapsHeuristic() {
        val pattern = rule(spend1, "MIGROS KADIKOY")
        assertEquals("ZARA ISTINYE PARK", extract(pattern, spend3))
    }

    @Test
    fun insertedWordLearnedFromHistory() {
        val sample = "Kartınızdan 250 TL harcama yapılmıştır."
        val history = listOf(
            "Kartınızdan bugün 80 TL harcama yapılmıştır.",
            "Kartınızdan 1.000 TL harcama yapılmıştır.",
            "Kartınızdan dün 35,90 TL harcama yapılmıştır."
        )
        val pattern = rule(sample, "250", history)
        assertEquals("80", extract(pattern, history[0]))
        assertEquals("35,90", extract(pattern, history[2]))
        assertEquals("12", extract(pattern, "Kartınızdan az önce 12 TL harcama yapılmıştır."))
    }

    @Test
    fun picksTheSelectedOfTwoAmounts() {
        val sample = "Kartınızdan 250,00 TL harcama yapılmıştır. Kullanılabilir limitiniz 4.750,00 TL'dir."
        val next = "Kartınızdan 99,90 TL harcama yapılmıştır. Kullanılabilir limitiniz 4.650,10 TL'dir."
        assertEquals("99,90", extract(rule(sample, "250,00"), next))
        assertEquals("4.650,10", extract(rule(sample, "4.750,00"), next))
    }

    @Test
    fun amountSelectedTogetherWithCurrency() {
        val pattern = rule(spend1, "12.312,50 TL")
        assertEquals("123 TL", extract(pattern, spend2))
    }

    @Test
    fun partialSelectionSnapsToWholeAmount() {
        val start = spend1.indexOf("12.312,50") + 2
        val result = NotificationPatternGenerator.generate(spend1, start, start + 4, emptyList())
        assertEquals("12.312,50", result?.value)
    }

    // --- Codes -------------------------------------------------------------------------------------

    @Test
    fun otpCode() {
        val sample = "Giriş için doğrulama kodunuz: 482193. Bu kodu kimseyle paylaşmayın. B001"
        val pattern = rule(sample, "482193")
        assertEquals("007341", extract(pattern, "Giriş için doğrulama kodunuz: 007341. Bu kodu kimseyle paylaşmayın. B001"))
    }

    @Test
    fun trackingCodeWithLettersAndDigits() {
        val sample = "Siparişiniz 1Z999AA10123456784 takip numarasıyla kargoya verilmiştir."
        val pattern = rule(sample, "1Z999AA10123456784")
        assertEquals("TR55K2", extract(pattern, "Siparişiniz TR55K2 takip numarasıyla kargoya verilmiştir."))
    }

    @Test
    fun deliveryCodeAfterApostropheSuffix() {
        val sample = "Paketiniz Trendyol Express'e teslim edildi. Teslimat kodu: 7731"
        val pattern = rule(sample, "7731")
        assertEquals("1200", extract(pattern, "Paketiniz Trendyol Express'e teslim edildi. Teslimat kodu: 1200"))
    }

    // --- Bills and dates ---------------------------------------------------------------------------

    private val bill1 = "Ekim 2026 dönemi faturanız 349,90 TL, son ödeme tarihi 25.10.2026. Detay: www.operator.com.tr/fatura"
    private val bill2 = "Kasım 2026 dönemi faturanız 1.049,00 TL, son ödeme tarihi 25.11.2026. Detay: www.operator.com.tr/fatura"

    @Test
    fun billAmount() {
        assertEquals("1.049,00", extract(rule(bill1, "349,90"), bill2))
    }

    @Test
    fun dueDate() {
        assertEquals("25.11.2026", extract(rule(bill1, "25.10.2026"), bill2))
    }

    @Test
    fun textualDate() {
        val sample = "Randevunuz 14 Ekim 2026 saat 10:30'da onaylanmıştır."
        val next = "Randevunuz 3 Kasım 2026 saat 09:00'da onaylanmıştır."
        assertEquals("3 Kasım 2026", extract(rule(sample, "14 Ekim 2026"), next))
        assertEquals("09:00", extract(rule(sample, "10:30"), next))
    }

    // --- Robustness --------------------------------------------------------------------------------

    @Test
    fun caseAndTurkishLettersAreIgnored() {
        val pattern = rule(spend1, "12.312,50")
        assertEquals("75", extract(pattern, spend2.uppercase().replace("123", "75")))
    }

    @Test
    fun unrelatedMessageDoesNotMatch() {
        val pattern = rule(spend1, "12.312,50", listOf(spend2, spend3))
        assertNull(extract(pattern, "Kampanya: bu hafta tüm alışverişlerinizde 3 kat puan!"))
        assertNull(extract(pattern, "Giriş için doğrulama kodunuz: 482193."))
    }

    @Test
    fun dissimilarHistoryIsIgnored() {
        val noisy = listOf("Kampanya: 3 kat puan!", "Şifreniz 1234", "İyi bayramlar dileriz")
        val pattern = rule(spend1, "12.312,50", noisy)
        assertEquals("123", extract(pattern, spend2))
    }

    @Test
    fun anchorWordRepeatedEarlierInMessage() {
        val sample = "Tutar bilgisi: Ödenen tutar 120 TL, kalan tutar 480 TL."
        val next = "Tutar bilgisi: Ödenen tutar 60 TL, kalan tutar 540 TL."
        assertEquals("540", extract(rule(sample, "480"), next))
        assertEquals("60", extract(rule(sample, "120"), next))
    }

    @Test
    fun wholeMessageSelected() {
        val pattern = rule("4821", "4821")
        assertEquals("7730", extract(pattern, "7730"))
    }

    @Test
    fun blankSelectionGivesNoRule() {
        assertNull(NotificationPatternGenerator.generate(spend1, 2, 2, emptyList()))
        assertNull(NotificationPatternGenerator.generate("   ", 0, 3, emptyList()))
    }

    @Test
    fun tokenizerRecognisesTypes() {
        val kinds = NotificationPatternGenerator.tokenize(spend1).map { it.kind }
        assertEquals(Kind.DATE, kinds[0])
        assertEquals(Kind.TIME, kinds[1])
        assertEquals(Kind.CARD, NotificationPatternGenerator.tokenize(spend1).first { it.text.contains('*') }.kind)
        assertEquals(
            Kind.NUMBER,
            NotificationPatternGenerator.tokenize(spend1).first { it.text == "12.312,50" }.kind
        )
    }
}
