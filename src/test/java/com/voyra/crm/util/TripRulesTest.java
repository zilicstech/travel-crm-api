package com.voyra.crm.util;

import com.voyra.crm.dto.FlightSectorDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripRulesTest {

    private static final LocalDate D10 = LocalDate.of(2026, 12, 10);
    private static final LocalDate D12 = LocalDate.of(2026, 12, 12);

    @Test
    void aStayChecksOutAfterItChecksInWithAtLeastOneRoom() {
        assertThatThrownBy(() -> TripRules.stay(D12, D10, 1, null)).hasMessageContaining("Check-out");
        assertThatThrownBy(() -> TripRules.stay(D12, D12, 1, null)).hasMessageContaining("Check-out");
        assertThatThrownBy(() -> TripRules.stay(D10, D12, 0, null)).hasMessageContaining("Rooms");
        assertThatThrownBy(() -> TripRules.stay(D10, D12, -2, null)).hasMessageContaining("Rooms");
        assertThatCode(() -> TripRules.stay(D10, D12, 1, 2)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.stay(null, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void aFlightReturnsOnOrAfterItDeparts() {
        assertThatThrownBy(() -> TripRules.flight(D12, D10)).hasMessageContaining("return");
        assertThatCode(() -> TripRules.flight(D10, D10)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TripRules.sectors(List.of(new FlightSectorDto("BOM", "DXB", D12),
                new FlightSectorDto("DXB", "BOM", D10)))).hasMessageContaining("leg");
        assertThatCode(() -> TripRules.sectors(List.of(new FlightSectorDto("BOM", "DXB", D10),
                new FlightSectorDto("DXB", "BOM", null), new FlightSectorDto("DXB", "BOM", D12)))).doesNotThrowAnyException();
    }

    @Test
    void aTransferHasAClockTimeAndAtLeastOnePassenger() {
        assertThatThrownBy(() -> TripRules.transfer("25:99", null)).hasMessageContaining("HH:mm");
        assertThatThrownBy(() -> TripRules.transfer("14:30", 0)).hasMessageContaining("Passengers");
        assertThatCode(() -> TripRules.transfer("23:59", 3)).doesNotThrowAnyException();
        assertThatCode(() -> TripRules.transfer("", null)).doesNotThrowAnyException();
    }
}
