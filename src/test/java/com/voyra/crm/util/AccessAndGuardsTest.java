package com.voyra.crm.util;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentMode;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.security.SystemActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The small access rules and guards in util: who may reach a booking or lead, and what the mirror refuses. */
@ExtendWith(MockitoExtension.class)
class AccessAndGuardsTest {

    @Mock LeadServiceRepository leadServiceRepository;
    @Mock AgentRepository agentRepository;

    @Test
    void anAgentReachesABookingTheyLoggedAreAssignedToOrWhoseTypeTheyManage() {
        Booking own = Booking.builder().agentId("A1").build();
        Booking assigned = Booking.builder().agentId("X").serviceAgentId("A1").build();
        Booking managed = Booking.builder().agentId("X").serviceAgentId("Y").serviceType(ServiceType.VISA).build();
        Booking standalone = Booking.builder().agentId("X").build();

        assertThat(BookingAccessChecker.agentCanAccess(own, "A1", List.of())).isTrue();
        assertThat(BookingAccessChecker.agentCanAccess(assigned, "A1", List.of())).isTrue();
        assertThat(BookingAccessChecker.agentCanAccess(managed, "A1", List.of(ServiceType.VISA))).isTrue();
        assertThat(BookingAccessChecker.agentCanAccess(managed, "A1", List.of(ServiceType.FLIGHT))).isFalse();
        assertThat(BookingAccessChecker.agentCanAccess(standalone, "A1", List.of(ServiceType.VISA))).isFalse();
    }

    @Test
    void anUnknownAgentHasNoServiceAccessAndTheServicesAreNotRead() {
        when(agentRepository.findById("A1")).thenReturn(Optional.empty());

        assertThat(LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, "L1", "A1")).isFalse();
        verifyNoInteractions(leadServiceRepository);
    }

    @Test
    void anAgentReachesALeadThroughAnAssignedOrManagedService() {
        Agent agent = Agent.builder().id("A1").manageableServices(List.of(ServiceType.HOTEL)).build();
        when(agentRepository.findById("A1")).thenReturn(Optional.of(agent));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L1")).thenReturn(List.of(
                LeadService.builder().assignedAgentId("A1").type(ServiceType.FLIGHT).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L2")).thenReturn(List.of(
                LeadService.builder().assignedAgentId("B").type(ServiceType.HOTEL).build()));
        when(leadServiceRepository.findByLeadIdOrderBySortOrderAsc("L3")).thenReturn(List.of(
                LeadService.builder().assignedAgentId("B").type(ServiceType.VISA).build()));

        assertThat(LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, "L1", "A1")).isTrue();
        assertThat(LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, "L2", "A1")).isTrue();
        assertThat(LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, "L3", "A1")).isFalse();
    }

    @Test
    void aMirroredBookingRefusesEveryoneButTheSystemActor() {
        assertThatCode(() -> PlatformManaged.check(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PlatformManaged.check(true))
                .isInstanceOf(BookingConflictException.class).hasMessage("Managed from Platform Bookings");
        assertThat(SystemActor.call("t1", () -> {
            PlatformManaged.check(true);
            return "ok";
        })).isEqualTo("ok");
    }

    @Test
    void onlyTheSystemActorRecordsAVoyraWalletPayment() {
        assertThatCode(() -> PlatformManaged.checkPaymentMode(PaymentMode.CASH)).doesNotThrowAnyException();
        assertThatThrownBy(() -> PlatformManaged.checkPaymentMode(PaymentMode.VOYRA_WALLET))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Voyra Wallet payments are recorded by Voyra only");
        assertThat(SystemActor.call("t1", () -> {
            PlatformManaged.checkPaymentMode(PaymentMode.VOYRA_WALLET);
            return "ok";
        })).isEqualTo("ok");
    }

    @Test
    void aGstinMustAgreeWithItsStateUnlessEitherIsBlank() {
        assertThatCode(() -> ValidationPatterns.requireGstinMatchesState(null, "27")).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requireGstinMatchesState(" ", "27")).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requireGstinMatchesState("27AAAAA0000A1Z5", null)).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requireGstinMatchesState("27AAAAA0000A1Z5", " ")).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requireGstinMatchesState(" 27AAAAA0000A1Z5 ", " 27 ")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ValidationPatterns.requireGstinMatchesState("27AAAAA0000A1Z5", "29"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This GSTIN is registered in state 27, but the GST state is 29 - the first two digits of a GSTIN are its state code");
        assertThatThrownBy(() -> ValidationPatterns.requireGstinMatchesState("2", "27"))
                .hasMessageStartingWith("This GSTIN is registered in state 2,");
    }

    @Test
    void aPhoneMustLookLikeOne() {
        assertThatCode(() -> ValidationPatterns.requirePhone(null)).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requirePhone("")).doesNotThrowAnyException();
        assertThatCode(() -> ValidationPatterns.requirePhone("+91 (22) 555-0100")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ValidationPatterns.requirePhone("abc"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ValidationPatterns.PHONE_MESSAGE);
    }

    @Test
    void serviceAndBookingTypesMapBothWays() {
        for (ServiceType s : ServiceType.values()) {
            assertThat(ServiceBookingTypeMapper.forBooking(ServiceBookingTypeMapper.forService(s))).isEqualTo(s);
            assertThat(ServiceBookingTypeMapper.forService(s).name()).isEqualTo(s.name());
        }
        assertThat(ServiceBookingTypeMapper.forBooking(BookingType.PACKAGE)).isNull();
    }

    @Test
    void aTenantIdMapsToALowerCaseSchemaWithoutDashes() {
        assertThat(TenantSchemaUtil.toSchemaName("F47AC10B-58CC")).isEqualTo("tenant_f47ac10b58cc");
        assertThat(TenantSchemaUtil.toSchemaName(null)).isNull();
        assertThat(TenantSchemaUtil.toSchemaName(" ")).isNull();
    }

    @Test
    void pageAndSizeAreBounded() {
        assertThat(PageRequestUtil.resolve(null, 10)).isNull();
        assertThat(PageRequestUtil.resolve(-3, null)).satisfies(p -> {
            assertThat(p.getPageNumber()).isZero();
            assertThat(p.getPageSize()).isEqualTo(25);
        });
        assertThat(PageRequestUtil.resolve(2, 0).getPageSize()).isEqualTo(1);
        assertThat(PageRequestUtil.resolve(2, 999).getPageSize()).isEqualTo(200);
        assertThat(PageRequestUtil.resolve(2, 50).getPageNumber()).isEqualTo(2);
    }

    @Test
    void aGeneratedFileBehavesLikeAnUpload(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        ByteArrayMultipartFile file = new ByteArrayMultipartFile("a.pdf", "application/pdf", new byte[]{1, 2, 3});
        ByteArrayMultipartFile empty = new ByteArrayMultipartFile("e.pdf", "application/pdf", new byte[0]);
        java.nio.file.Path dest = dir.resolve("out.bin");

        file.transferTo(dest.toFile());

        assertThat(file.getName()).isEqualTo("file");
        assertThat(file.getOriginalFilename()).isEqualTo("a.pdf");
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.isEmpty()).isFalse();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(file.getSize()).isEqualTo(3);
        assertThat(file.getBytes()).containsExactly(1, 2, 3);
        assertThat(file.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
        assertThat(java.nio.file.Files.readAllBytes(dest)).containsExactly(1, 2, 3);
    }

    @Test
    void aQrCodeRendersAndAnEmptyPayloadFails() {
        assertThat(QrCodeRenderer.render("pay me", 64).getWidth()).isEqualTo(64f);
        assertThatThrownBy(() -> QrCodeRenderer.render("", 64))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to render QR code");
    }
}
