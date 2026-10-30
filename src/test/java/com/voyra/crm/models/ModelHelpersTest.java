package com.voyra.crm.models;

import com.voyra.crm.entity.BookingDocument;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.SupplierProvider;
import com.voyra.crm.util.FxMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ModelHelpersTest {

    @Test
    void figuresTypedInTheBaseCurrencyAreNotKeptAsForeign() {
        BookingDocument doc = new BookingDocument();
        FxMath.Converted inBase = new FxMath.Converted("INR", new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100"));
        doc.keepForeign(new FxMath.Pricing(BigDecimal.TEN, BigDecimal.ONE, inBase, inBase), "INR");
        assertThat(doc.getPurchaseCurrency()).isNull();
        assertThat(doc.getPurchasePrice()).isNull();
        assertThat(doc.getMarkupCurrency()).isNull();
        assertThat(doc.getMarkupInput()).isNull();
    }

    @Test
    void foreignFiguresAreKept() {
        BookingDocument doc = new BookingDocument();
        FxMath.Converted usd = new FxMath.Converted("USD", new BigDecimal("10"), new BigDecimal("83"), new BigDecimal("830"));
        doc.keepForeign(new FxMath.Pricing(BigDecimal.TEN, BigDecimal.ONE, usd, usd), "INR");
        assertThat(doc.getPurchaseCurrency()).isEqualTo("USD");
        assertThat(doc.getPurchaseRate()).isEqualByComparingTo("83");
        assertThat(doc.getMarkupInput()).isEqualByComparingTo("10");
    }

    @Test
    void aCredentialNeverPrintsItsSecrets() {
        String text = new ResolvedCredential(SupplierProvider.TRIPJACK, SupplierEnvironment.values()[0], "https://api.example.test",
                "key-123456789", "abc").toString();
        assertThat(text).contains("apiKey=••••••6789", "userId=••••••").doesNotContain("key-123456789", "abc]");
        assertThat(new ResolvedCredential(SupplierProvider.TRIPJACK, null, null, null, null).toString())
                .contains("apiKey=null, userId=null");
    }
}
