package com.voyra.crm.service;

import com.voyra.crm.dto.BankTopupCreateRequest;
import com.voyra.crm.dto.TopupApproveRequest;
import com.voyra.crm.dto.TopupRejectRequest;
import com.voyra.crm.dto.TopupResponse;
import com.voyra.crm.entity.TopupRequest;
import com.voyra.crm.entity.Wallet;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.TopupMode;
import com.voyra.crm.enums.TopupStatus;
import com.voyra.crm.enums.WalletStatus;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.GatewayOrderRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.TopupRequestRepository;
import com.voyra.crm.repository.WalletEntryRepository;
import com.voyra.crm.repository.WalletRepository;
import com.voyra.crm.service.payments.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopupServiceCoverageTest {

    private static final byte[] PDF = "%PDF-1.4 test".getBytes();

    @Mock private TopupRequestRepository topupRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private WalletService walletService;
    @Mock private FileStorageService fileStorageService;
    @Mock private PlatformAuditService platformAuditService;
    @Mock private GatewayOrderRepository gatewayOrderRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private GatewayWebhookService webhookService;
    @Mock private NotificationService notificationService;
    @Mock private WalletEntryRepository entryRepository;
    @InjectMocks private TopupService service;

    private void openWallet() {
        when(walletRepository.findByTenantId("T1")).thenReturn(Optional.of(Wallet.builder().id("W1").tenantId("T1").status(WalletStatus.OPEN).build()));
    }

    private static BankTopupCreateRequest request(String ref, String fileName, byte[] bytes) {
        BankTopupCreateRequest r = new BankTopupCreateRequest();
        r.setAmount(new BigDecimal("50000.00"));
        r.setBankRef(ref);
        r.setProof(new MockMultipartFile("proof", fileName, "application/pdf", bytes));
        return r;
    }

    @Test
    void anAgencyWithoutAWalletCannotTopUp() {
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("UTR12345", "slip.pdf", PDF)))
                .isInstanceOf(NotFoundException.class).hasMessage("Wallet not found for this agency");
    }

    @Test
    void aBankReferenceNeedsFourLettersOrDigits() {
        openWallet();
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request(" ab c ", "slip.pdf", PDF)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("bankRef"))
                .hasMessage("Bank reference must be at least 4 letters or digits");
        verifyNoInteractions(topupRepository);
    }

    @Test
    void aProofMustBeAPdfOrAnImageWhateverItsNameSays() {
        openWallet();
        org.springframework.web.multipart.MultipartFile unnamed = org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        when(unnamed.getOriginalFilename()).thenReturn(null);
        BankTopupCreateRequest noName = request("UTR12345", "x.pdf", PDF);
        noName.setProof(unnamed);
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", noName))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        for (String name : new String[]{"slip.exe", "slip"}) {
            assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("UTR12345", name, PDF)))
                    .as(String.valueOf(name)).isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        }
        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("UTR12345", "slip.png", PDF)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Upload a PDF, JPG, PNG or WEBP file");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void aProofOverFiveMegabytesSaysItIsTooLarge() {
        openWallet();
        byte[] big = new byte[(int) TopupService.MAX_PROOF_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        assertThatThrownBy(() -> service.createBank("T1", "U1", "Asha", request("UTR12345", "slip.pdf", big)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("File is larger than 5 MB");
    }

    @Test
    void aLongProofNameKeepsItsLast255CharactersAndANoteIsTrimmed() {
        openWallet();
        when(fileStorageService.store(eq("platform"), eq("wallet-topups/T1"), anyString(), any())).thenReturn("k");
        when(topupRepository.save(any(TopupRequest.class))).thenAnswer(i -> i.getArgument(0));
        String name = "x".repeat(300) + ".pdf";
        BankTopupCreateRequest r = request("  utr 12345 ", name, PDF);
        r.setNote("  paid from HDFC  ");

        TopupResponse response = service.createBank("T1", "U1", "Asha", r);

        ArgumentCaptor<TopupRequest> saved = ArgumentCaptor.forClass(TopupRequest.class);
        verify(topupRepository).save(saved.capture());
        assertThat(saved.getValue().getProofFileName()).hasSize(255).endsWith(".pdf");
        assertThat(saved.getValue().getProofContentType()).isEqualTo("application/pdf");
        assertThat(saved.getValue().getBankRef()).isEqualTo("utr 12345");
        assertThat(saved.getValue().getBankRefNormalized()).isEqualTo("UTR12345");
        assertThat(response.getNote()).isEqualTo("paid from HDFC");
        assertThat(response.getTenantId()).isNull();
    }

    @Test
    void anAgencySeesItsOwnTopups() {
        when(topupRepository.findByTenantIdOrderByCreatedAtDesc("T1")).thenReturn(List.of(
                TopupRequest.builder().id("R1").tenantId("T1").mode(TopupMode.GATEWAY).status(TopupStatus.APPROVED).build()));

        assertThat(service.listForAgency("T1")).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("R1");
            assertThat(r.getAgencyName()).isNull();
        });
    }

    @Test
    void aSimulatedPaymentNeedsTheCallersOwnGatewayTopup() {
        when(paymentGateway.simulateEnabled()).thenReturn(true);
        when(topupRepository.findById("R1")).thenReturn(Optional.of(TopupRequest.builder().id("R1").tenantId("T2").mode(TopupMode.GATEWAY).build()));
        when(topupRepository.findById("R2")).thenReturn(Optional.of(TopupRequest.builder().id("R2").tenantId("T1").mode(TopupMode.BANK_TRANSFER).build()));

        assertThatThrownBy(() -> service.simulate("T1", "R1")).isInstanceOf(NotFoundException.class).hasMessage("Top-up not found");
        assertThatThrownBy(() -> service.simulate("T1", "R2")).isInstanceOf(NotFoundException.class).hasMessage("Top-up not found");
        assertThatThrownBy(() -> service.simulate("T1", "R9")).isInstanceOf(NotFoundException.class).hasMessage("Top-up not found");
        verifyNoInteractions(webhookService);
    }

    @Test
    void decisionsNeedAPendingBankTransfer() {
        TopupRejectRequest reject = new TopupRejectRequest();
        reject.setNote("no");
        assertThatThrownBy(() -> service.reject("R9", "P1", reject)).isInstanceOf(NotFoundException.class).hasMessage("Top-up not found");
        when(topupRepository.findByIdForUpdate("R1")).thenReturn(Optional.of(
                TopupRequest.builder().id("R1").status(TopupStatus.APPROVED).mode(TopupMode.BANK_TRANSFER).build()));
        when(topupRepository.findByIdForUpdate("R2")).thenReturn(Optional.of(
                TopupRequest.builder().id("R2").status(TopupStatus.PENDING).mode(TopupMode.GATEWAY).build()));
        assertThatThrownBy(() -> service.approve("R1", "P1", new TopupApproveRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("This top-up was already approved");
        assertThatThrownBy(() -> service.reject("R2", "P1", reject))
                .isInstanceOf(IllegalStateException.class).hasMessage("Only bank-transfer top-ups are decided by hand");
        verifyNoInteractions(walletService, platformAuditService);
    }

    @Test
    void openingAProofIsAuditedAndServedByItsExtension() {
        when(topupRepository.findById("R1")).thenReturn(Optional.of(TopupRequest.builder().id("R1").tenantId("T1")
                .proofFileKey("k/slip.png").proofFileName("slip.png").proofContentType("text/html").build()));
        ByteArrayResource resource = new ByteArrayResource(new byte[]{1});
        when(fileStorageService.retrieve("k/slip.png")).thenReturn(resource);

        TopupService.ProofContent proof = service.openProof("R1");

        assertThat(proof.resource()).isSameAs(resource);
        assertThat(proof.fileName()).isEqualTo("slip.png");
        assertThat(proof.contentType()).isEqualTo("image/png");
        verify(platformAuditService).record(PlatformAuditAction.TOPUP_PROOF_VIEW, "TOPUP_REQUEST", "R1", Map.of("tenantId", "T1"));
    }

    @Test
    void aProofWithAnUnknownTypeIsServedAsBytesAndAMissingOneIsNotFound() {
        when(topupRepository.findById("R1")).thenReturn(Optional.of(TopupRequest.builder().id("R1").tenantId("T1")
                .proofFileKey("k/slip").proofFileName("slip.unknownext").build()));
        when(topupRepository.findById("R2")).thenReturn(Optional.of(TopupRequest.builder().id("R2").tenantId("T1").build()));

        assertThat(service.openProof("R1").contentType()).isEqualTo("application/octet-stream");
        assertThatThrownBy(() -> service.openProof("R2")).isInstanceOf(NotFoundException.class).hasMessage("This top-up has no proof file");
        assertThatThrownBy(() -> service.openProof("R9")).isInstanceOf(NotFoundException.class).hasMessage("Top-up not found");
    }
}
