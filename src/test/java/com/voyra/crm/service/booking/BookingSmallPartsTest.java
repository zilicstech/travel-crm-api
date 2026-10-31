package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.FlightReviewRequest;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingEvent;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.exception.SupplierException;
import com.voyra.crm.repository.PlatformBookingEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** PaxCodec failures, the supplier refusal reader, the agency message and the small booking defaults. */
class BookingSmallPartsTest {

    private static final String KEY = "dGVzdC1vbmx5LWFlcy1rZXktMzItYnl0ZXMtbG9uZyE=";
    private final PaxCodec codec = new PaxCodec(new ObjectMapper(), KEY);

    @Test
    void anUnserialisableValueCannotBeEncryptedOrHashed() {
        Object notJson = new Object();

        assertThatThrownBy(() -> codec.encrypt(notJson)).isInstanceOf(IllegalStateException.class).hasMessage("Could not serialise passengers");
        assertThatThrownBy(() -> codec.hash(notJson)).isInstanceOf(IllegalStateException.class).hasMessage("Could not hash the confirm payload");
    }

    @Test
    void anEncryptedValueOfTheWrongShapeCannotBeRead() {
        String enc = codec.encrypt("just text");

        assertThatThrownBy(() -> codec.decrypt(enc, Map.class)).isInstanceOf(IllegalStateException.class).hasMessage("Could not read passengers");
    }

    @Test
    void theSameValueHashesTheSameAndAShortPassportIsFullyMasked() {
        assertThat(codec.hash(List.of("a", 1))).hasSize(64).isEqualTo(codec.hash(List.of("a", 1)));
        assertThat(codec.redact(Map.of("passport_number", "AB", "api_key", "x", "list", List.of("plain"))))
                .containsEntry("passport_number", "***").containsEntry("list", List.of("plain")).doesNotContainKey("api_key");
    }

    // ---------------------------------------------------------------- supplier refusals

    private static SupplierException refused(HttpStatus status, String body) {
        HttpClientErrorException http = HttpClientErrorException.create(status, status.getReasonPhrase(), null,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return new SupplierException("x", http);
    }

    @Test
    void onlyANonTimeout4xxIsARefusal() {
        Logger log = mock(Logger.class);

        assertThat(SupplierRefusal.of(new SupplierException("x", null), log)).isNull();
        assertThat(SupplierRefusal.of(new SupplierException("x", HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null)), log)).isNull();
        assertThat(SupplierRefusal.of(refused(HttpStatus.REQUEST_TIMEOUT, "{}"), log)).isNull();
        verifyNoInteractions(log);
    }

    @Test
    void aRefusalWithoutAJsonBodyStillNamesTheStatus() {
        Logger log = mock(Logger.class);

        assertThat(SupplierRefusal.of(refused(HttpStatus.BAD_REQUEST, "<html>"), log)).isEqualTo("Supplier refused (HTTP 400)");
        verify(log).warn("{}", "Supplier refused (HTTP 400)");
    }

    @Test
    void aLongRefusalIsCutToThreeHundredCharacters() {
        String body = "{\"errors\":[{\"errCode\":\"E1\",\"message\":\"" + "m".repeat(400) + "\"}]}";

        String refusal = SupplierRefusal.of(refused(HttpStatus.UNPROCESSABLE_ENTITY, body), mock(Logger.class));

        assertThat(refusal).hasSize(300).startsWith("Supplier refused (HTTP 422) E1: mmm");
    }

    // ---------------------------------------------------------------- agency message

    @Test
    void theAgencyMessageFollowsTheStatusAndTheSuppliersWords() {
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().build())).isNull();
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().status(PlatformBookingStatus.UNKNOWN).build()))
                .isEqualTo(AgencySupplierMessage.CONFIRMING);
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().status(PlatformBookingStatus.FAILED).build()))
                .isEqualTo(AgencySupplierMessage.FAILED);
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().status(PlatformBookingStatus.FAILED)
                .product(PricingProduct.HOTEL).supplierMessage("Room SOLD OUT").build())).isEqualTo("Room no longer available");
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().status(PlatformBookingStatus.FAILED)
                .product(PricingProduct.FLIGHT).supplierMessage("fare has changed").build())).isEqualTo("Fare no longer available");
        assertThat(AgencySupplierMessage.of(PlatformBooking.builder().status(PlatformBookingStatus.TICKETED).build())).isNull();
    }

    // ---------------------------------------------------------------- defaults

    @Test
    void aReviewRequestSendsTheReturnOfferOnlyWhenThereIsOne() {
        FlightBookingProvider provider = mock(FlightBookingProvider.class, CALLS_REAL_METHODS);
        FlightReviewRequest r = new FlightReviewRequest();
        r.setOfferId("ON");
        r.setAdults(2);
        r.setChildren(1);
        r.setInfants(0);

        provider.review(r);
        r.setReturnOfferId(" ");
        assertThat(FlightBookingProvider.offerIds(r)).containsExactly("ON");
        r.setReturnOfferId("RET");

        assertThat(FlightBookingProvider.offerIds(r)).containsExactly("ON", "RET");
        verify(provider).review(List.of("ON"), 2, 1, 0);
    }

    @Test
    void anEventWithoutAPayloadIsRecordedWithoutOne() {
        PlatformBookingEventRepository repo = mock(PlatformBookingEventRepository.class);
        when(repo.existsById(any())).thenReturn(false);
        PlatformBookingEventRecorder recorder = new PlatformBookingEventRecorder(repo, codec);

        recorder.record("PB1", "NOTE", null);

        ArgumentCaptor<PlatformBookingEvent> saved = ArgumentCaptor.forClass(PlatformBookingEvent.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getPlatformBookingId()).isEqualTo("PB1");
        assertThat(saved.getValue().getType()).isEqualTo("NOTE");
        assertThat(saved.getValue().getPayload()).isNull();
        assertThat(saved.getValue().getCreatedAt()).isNotNull();
    }
}
