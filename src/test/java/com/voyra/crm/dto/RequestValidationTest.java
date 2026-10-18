package com.voyra.crm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** B7, CRC-02, CRC-04, CRD-02, CRD-03, CRD-20: the field rules the forms rely on, checked at the DTO. */
class RequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Set<String> badFields(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString).collect(Collectors.toSet());
    }

    @Test
    void phoneAcceptsDigitsAndPunctuationOnly() {
        AgentUpdateRequest agent = new AgentUpdateRequest();
        agent.setPhone("abc-not-a-phone");
        assertThat(badFields(agent)).contains("phone");
        agent.setPhone("+91 (982) 000-0000");
        assertThat(badFields(agent)).doesNotContain("phone");
        agent.setPhone("");
        assertThat(badFields(agent)).doesNotContain("phone");
        agent.setPhone("12345");
        assertThat(badFields(agent)).contains("phone");
    }

    @Test
    void memberDobCannotBeInTheFuture() {
        MemberUpdateRequest member = new MemberUpdateRequest();
        member.setDob(LocalDate.now().plusDays(1));
        assertThat(badFields(member)).contains("dob");
        member.setDob(LocalDate.now());
        assertThat(badFields(member)).doesNotContain("dob");
    }

    @Test
    void gstinMustBeAValidFormatInAnyCase() {
        ClientUpdateRequest client = new ClientUpdateRequest();
        client.setGstin("BADGSTIN123");
        assertThat(badFields(client)).contains("gstin");
        client.setGstin("27aabcu9603r1zm");
        assertThat(badFields(client)).doesNotContain("gstin");
    }

    @Test
    void vendorAccountingFieldsAreBounded() {
        VendorUpdateRequest vendor = new VendorUpdateRequest();
        vendor.setPaymentTermsDays(-3);
        vendor.setCreditLimitInr(new BigDecimal("-100"));
        vendor.setTdsRatePercent(new BigDecimal("150"));
        vendor.setBankIfsc("BADIFSC");
        assertThat(badFields(vendor)).contains("paymentTermsDays", "creditLimitInr", "tdsRatePercent", "bankIfsc");
        vendor.setPaymentTermsDays(15);
        vendor.setCreditLimitInr(BigDecimal.ZERO);
        vendor.setTdsRatePercent(new BigDecimal("100"));
        vendor.setBankIfsc("HDFC0000123");
        assertThat(badFields(vendor)).doesNotContain("paymentTermsDays", "creditLimitInr", "tdsRatePercent", "bankIfsc");
    }

    @Test
    void taxSlabPercentsAreAtMostOneHundred() {
        TaxRateConfigCreateRequest tax = new TaxRateConfigCreateRequest();
        tax.setRatePercent(new BigDecimal("150"));
        tax.setTaxablePercent(new BigDecimal("150"));
        assertThat(badFields(tax)).contains("ratePercent", "taxablePercent");
    }

    @Test
    void agencyProfileIsValidated() {
        AgencyProfileUpdateRequest profile = new AgencyProfileUpdateRequest();
        profile.setGstNumber("NOPE");
        profile.setBankIfscCode("BADIFSC");
        profile.setDefaultCommission(new BigDecimal("101"));
        assertThat(badFields(profile)).contains("gstNumber", "bankIfscCode", "defaultCommission");
        profile.setGstNumber("27AABCU9603R1ZM");
        profile.setBankIfscCode("icic0000055");
        profile.setDefaultCommission(new BigDecimal("2"));
        assertThat(badFields(profile)).isEmpty();
    }

    @Test
    void visaFieldsNameThemselvesWhenTooLong() {
        VisaCreateRequest visa = new VisaCreateRequest();
        visa.setClientId("C1");
        visa.setCountry("France");
        visa.setVisaType("x".repeat(61));
        visa.setPassportNumber("x".repeat(25));
        assertThat(badFields(visa)).containsExactlyInAnyOrder("visaType", "passportNumber");
    }
}
