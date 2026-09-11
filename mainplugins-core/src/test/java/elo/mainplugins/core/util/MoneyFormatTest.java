package elo.mainplugins.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyFormatTest {

    @Test
    void wholeAmountHasNoDecimals() {
        assertEquals("100", MoneyFormat.pelna(100));
        assertEquals("1,500", MoneyFormat.pelna(1500));
        assertEquals("0", MoneyFormat.pelna(0));
    }

    @Test
    void amountWithCentsHasTwoDecimals() {
        assertEquals("100.50", MoneyFormat.pelna(100.5));
        assertEquals("0.25", MoneyFormat.pelna(0.25));
        assertEquals("1,234.05", MoneyFormat.pelna(1234.05));
    }
}
