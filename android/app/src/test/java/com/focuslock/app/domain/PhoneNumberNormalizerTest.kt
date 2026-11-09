package com.focuslock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test fun keepsInternationalPrefixAndDigits() {
        assertEquals("+12025550123", PhoneNumberNormalizer.normalize("+1 (202) 555-0123"))
    }

    @Test fun convertsArabicIndicDigits() {
        assertEquals("1234567890", PhoneNumberNormalizer.normalize("١٢٣٤٥٦٧٨٩٠"))
    }

    @Test fun rejectsTooShortNumbers() {
        assertNull(PhoneNumberNormalizer.normalize("12345"))
    }

    @Test fun plusIsAllowedOnlyAtTheBeginning() {
        assertEquals("123456789", PhoneNumberNormalizer.normalize("12+34-56789"))
    }

    @Test fun equivalentMatchesSameCompleteNumberWithFormattingDifferences() {
        assertEquals(true, PhoneNumberNormalizer.equivalent("+1 (202) 555-0123", "+12025550123"))
    }

    @Test fun equivalentMatchesUnicodeDigitsAfterNormalization() {
        assertEquals(true, PhoneNumberNormalizer.equivalent("+966 50 123 4567", "+٩٦٦٥٠١٢٣٤٥٦٧"))
    }

    @Test fun equivalentDoesNotInferCountryCodeOrTrunkPrefix() {
        assertEquals(false, PhoneNumberNormalizer.equivalent("0501234567", "+966501234567"))
        assertEquals(false, PhoneNumberNormalizer.equivalent("501234567", "+966501234567"))
    }

    @Test fun equivalentRejectsDifferentCountryCodesWithSameNationalSuffix() {
        assertEquals(false, PhoneNumberNormalizer.equivalent("+966501234567", "+971501234567"))
        assertEquals(false, PhoneNumberNormalizer.equivalent("+12025550123", "+442025550123"))
    }

    @Test fun equivalentRejectsDifferentNumbers() {
        assertEquals(false, PhoneNumberNormalizer.equivalent("0501234567", "+966509876543"))
    }

    @Test fun equivalentRejectsShortAndEmptyNumbers() {
        assertEquals(false, PhoneNumberNormalizer.equivalent("123456", "123456"))
        assertEquals(false, PhoneNumberNormalizer.equivalent("", ""))
        assertEquals(false, PhoneNumberNormalizer.equivalent("not a number", ""))
    }
}
