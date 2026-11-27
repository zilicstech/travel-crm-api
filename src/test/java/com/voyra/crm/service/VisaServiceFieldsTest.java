package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.dto.VisaFileResponse;
import com.voyra.crm.dto.VisaResponse;
import com.voyra.crm.dto.VisaValuesUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Member;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.Vendor;
import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.entity.VisaFile;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.MemberRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VendorRepository;
import com.voyra.crm.repository.VisaFileRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisaServiceFieldsTest {

    @Mock VisaRepository visaRepository;
    @Mock ClientRepository clientRepository;
    @Mock AgentRepository agentRepository;
    @Mock TenantRepository tenantRepository;
    @Mock AuditService auditService;
    @Mock MemberRepository memberRepository;
    @Mock VendorRepository vendorRepository;
    @Mock VisaFieldService visaFieldService;
    @Mock VisaFileRepository visaFileRepository;
    @Mock FileStorageService fileStorage;
    @Mock AuthorResolver authorResolver;
    @InjectMocks VisaService service;

    private final VisaField nationalityNote = field("note", VisaFieldType.TEXT, false);
    private final VisaField medical = field("med", VisaFieldType.DROPDOWN, true, "RECVD", "PENDING");
    private final VisaField contract = field("con", VisaFieldType.FILE, true);
    private final VisaField photo = field("pho", VisaFieldType.FILE, false);
    private final MultipartFile pdf = new MockMultipartFile("con", "contract.pdf", "application/pdf", "%PDF-1.4 x".getBytes());

    private static VisaField field(String id, VisaFieldType type, boolean mandatory, String... options) {
        return VisaField.builder().id(id).scope(VisaFieldScope.COUNTRY).country("Kenya").label(id.toUpperCase()).type(type)
                .options(List.of(options)).mandatory(mandatory).active(true).build();
    }

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        signIn("T1", UserType.AGENCY_OWNER);
        given(tenantRepository.findById("T1")).willReturn(Optional.of(Tenant.builder().id("T1").ownerName("Dana").build()));
        given(clientRepository.findById("C1")).willReturn(Optional.of(Client.builder().id("C1").name("Asha").build()));
        given(memberRepository.findById("M1")).willReturn(Optional.of(Member.builder().memberId("M1").clientId("C1").name("Ravi")
                .passportNumber("Z123").passportExpiry(LocalDate.of(2031, 1, 1)).nationality("Indian").build()));
        given(memberRepository.findById("M9")).willReturn(Optional.of(Member.builder().memberId("M9").clientId("C2").name("Other").build()));
        given(visaFieldService.activeFor(anyString())).willReturn(List.of(nationalityNote, medical, contract, photo));
        given(visaRepository.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));
        given(visaFileRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(fileStorage.store(eq("T1"), eq("visa-files"), anyString(), any())).willReturn("key-new");
        given(authorResolver.resolveCurrentAuthor()).willReturn(new AuthorResolver.AuthorInfo("T1", "Dana"));
        given(vendorRepository.findById("V1")).willReturn(Optional.of(Vendor.builder().id("V1").name("Atlas Visas").build()));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    private static VisaCreateRequest open(Map<String, String> values) {
        VisaCreateRequest r = new VisaCreateRequest();
        r.setClientId("C1");
        r.setMemberId("M1");
        r.setCountry("Kenya");
        r.setVisaType("Tourist");
        r.setValues(values);
        return r;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) s.afterCommit();
            s.afterCompletion(status);
        });
    }

    private Visa stored(String agentId, long version) {
        Visa v = Visa.builder().id("VI1").clientId("C1").clientName("Asha").agentId(agentId).agentName("x").country("Kenya")
                .visaType("Tourist").status(VisaStatus.DOCUMENTS_PENDING).version(version)
                .fieldValues(new LinkedHashMap<>(Map.of("med", "PENDING", "note", "old"))).build();
        given(visaRepository.findById("VI1")).willReturn(Optional.of(v));
        return v;
    }

    @Test
    void createCopiesTheTravellersPassportFactsAndStoresTheFieldsAndFiles() {
        Map<String, MultipartFile> files = new HashMap<>();
        files.put("con", pdf);
        files.put("pho", new MockMultipartFile("pho", "", "image/png", new byte[0]));
        VisaCreateRequest r = open(map("med", "recvd", "note", "  "));
        r.setPurchasePrice(new BigDecimal("120.00"));
        r.setPurchaseCurrency("USD");
        r.setSellingPrice(new BigDecimal("12500"));
        r.setVendorId("V1");
        r.setRemarks("  ");
        r.setPassportNumber("  ");

        VisaResponse out = service.createVisa(r, files);

        assertThat(out.getMemberId()).isEqualTo("M1");
        assertThat(out.getMemberName()).isEqualTo("Ravi");
        assertThat(out.getPassportNumber()).isEqualTo("Z123");
        assertThat(out.getPassportExpiry()).isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(out.getNationality()).isEqualTo("Indian");
        assertThat(out.getValues()).containsOnly(Map.entry("med", "RECVD"));
        assertThat(out.getVendorName()).isEqualTo("Atlas Visas");
        assertThat(out.getRemarks()).isNull();
        assertThat(out.getFiles()).extracting(VisaFileResponse::getFieldId, VisaFileResponse::getFileName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("con", "contract.pdf"));
        verify(fileStorage).store(eq("T1"), eq("visa-files"), anyString(), eq(pdf));

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(fileStorage).delete("key-new");
    }

    @Test
    void givenPassportFactsWinOverTheTravellers() {
        VisaCreateRequest r = open(map("med", "RECVD"));
        r.setPassportNumber(" N999 ");
        r.setPassportExpiry(LocalDate.of(2030, 5, 5));
        r.setNationality("British");
        VisaResponse out = service.createVisa(r, Map.of("con", pdf));
        assertThat(out.getPassportNumber()).isEqualTo("N999");
        assertThat(out.getPassportExpiry()).isEqualTo(LocalDate.of(2030, 5, 5));
        assertThat(out.getNationality()).isEqualTo("British");
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(fileStorage, never()).delete(anyString());
    }

    @Test
    void theTravellerMustBelongToTheClient() {
        VisaCreateRequest r = open(map());
        r.setMemberId("M9");
        assertThatThrownBy(() -> service.createVisa(r, Map.of()))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("memberId"));
        r.setMemberId("nobody");
        assertThatThrownBy(() -> service.createVisa(r, Map.of())).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void missingMandatoryFieldsStoreNoFileAndNoRow() {
        assertThatThrownBy(() -> service.createVisa(open(map("note", "x")), Map.of("pho", pdf)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors())
                        .containsOnlyKeys("values.med", "values.con"));
        verify(fileStorage, never()).store(any(), any(), any(), any());
        verify(visaRepository, never()).saveAndFlush(any());
    }

    @Test
    void aBadFileIsRefusedBeforeAnythingIsStored() {
        MultipartFile exe = new MockMultipartFile("con", "run.exe", "application/octet-stream", new byte[]{1, 2});
        assertThatThrownBy(() -> service.createVisa(open(map("med", "RECVD")), Map.of("con", exe)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fileStorage, never()).store(any(), any(), any(), any());
    }

    @Test
    void aPurchasePriceNeedsItsCurrencyAndAVendorMustExist() {
        VisaCreateRequest r = open(map("med", "RECVD"));
        r.setPurchasePrice(BigDecimal.TEN);
        assertThatThrownBy(() -> service.createVisa(r, Map.of("con", pdf)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("purchaseCurrency"));
        r.setPurchaseCurrency(" ");
        assertThatThrownBy(() -> service.createVisa(r, Map.of("con", pdf))).isInstanceOf(FieldValidationException.class);
        r.setPurchaseCurrency("USD");
        r.setVendorId("V404");
        assertThatThrownBy(() -> service.createVisa(r, Map.of("con", pdf)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("vendorId"));
    }

    @Test
    void theJsonCreateWithNoValuesStillWorksWhenNothingIsMandatory() {
        given(visaFieldService.activeFor(anyString())).willReturn(List.of(nationalityNote));
        VisaCreateRequest r = open(null);
        assertThat(service.createVisa(r).getValues()).isEmpty();
    }

    @Test
    void anEditMergesPerKeyAndAuditsTheLabel() {
        Visa v = stored("T1", 3);
        VisaValuesUpdateRequest req = VisaValuesUpdateRequest.builder().version(3L)
                .values(map("med", "RECVD", "note", "")).remarks("Express").vendorId("V1").build();
        VisaResponse out = service.updateValues("VI1", req);
        assertThat(out.getValues()).containsOnly(Map.entry("med", "RECVD"));
        assertThat(v.getRemarks()).isEqualTo("Express");
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA), eq("VI1"), anyString(), changes.capture());
        assertThat(changes.getValue()).extracting(AuditChange::getField, AuditChange::getOldValue, AuditChange::getNewValue)
                .contains(org.assertj.core.groups.Tuple.tuple("MED", "PENDING", "RECVD"),
                        org.assertj.core.groups.Tuple.tuple("NOTE", "old", null),
                        org.assertj.core.groups.Tuple.tuple("remarks", null, "Express"),
                        org.assertj.core.groups.Tuple.tuple("vendorName", null, "Atlas Visas"));
    }

    /** The overall status is set by hand, audited like the other facts; leaving it out keeps it. */
    @Test
    void theOverallStatusIsSetByHandAndAudited() {
        Visa v = stored("T1", 0);
        v.setStatus(com.voyra.crm.enums.VisaStatus.DOCUMENTS_PENDING);

        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).status(com.voyra.crm.enums.VisaStatus.SUBMITTED).build());

        assertThat(v.getStatus()).isEqualTo(com.voyra.crm.enums.VisaStatus.SUBMITTED);
        verify(auditService).recordUpdate(eq(com.voyra.crm.enums.AuditEntityType.VISA), eq("VI1"), any(),
                org.mockito.ArgumentMatchers.argThat(changes -> changes.stream().anyMatch(c -> "status".equals(c.getField())
                        && "DOCUMENTS_PENDING".equals(c.getOldValue()) && "SUBMITTED".equals(c.getNewValue()))));
        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).remarks("x").build());
        assertThat(v.getStatus()).isEqualTo(com.voyra.crm.enums.VisaStatus.SUBMITTED);
    }

    @Test
    void everyOwnFactCanBeEditedAndClearedAndNoChangeSavesNothing() {
        Visa v = stored("T1", 0);
        v.setPurchaseCurrency("USD");
        v.setVendorId("V1");
        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).passportNumber("N1").passportExpiry(LocalDate.of(2032, 2, 2))
                .nationality("Kenyan").purchasePrice(BigDecimal.ONE).purchaseCurrency("EUR").sellingPrice(BigDecimal.TEN).build());
        assertThat(v.getPassportNumber()).isEqualTo("N1");
        assertThat(v.getPurchaseCurrency()).isEqualTo("EUR");
        assertThat(v.getSellingPrice()).isEqualTo(BigDecimal.TEN);
        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).passportNumber("").nationality(" ").vendorId("").remarks("").build());
        assertThat(v.getPassportNumber()).isNull();
        assertThat(v.getNationality()).isNull();
        assertThat(v.getVendorId()).isNull();
        assertThat(v.getVendorName()).isNull();

        org.mockito.Mockito.clearInvocations(visaRepository);
        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).values(map()).build());
        service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).values(map("med", "PENDING")).build());
        verify(visaRepository, never()).saveAndFlush(any());
    }

    @Test
    void aStaleVersionIsVisaChangedWithTheCurrentCase() {
        stored("T1", 4);
        assertThatThrownBy(() -> service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(3L).values(map("med", "RECVD")).build()))
                .isInstanceOfSatisfying(BookingConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("VISA_CHANGED");
                    assertThat(((VisaResponse) e.getDetails().get("visa")).getVersion()).isEqualTo(4L);
                });
    }

    @Test
    void aMandatoryFieldCannotBeClearedButALaterMandatoryOneIsNotRequiredElsewhere() {
        stored("T1", 0);
        assertThatThrownBy(() -> service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).values(map("med", "")).build()))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("values.med"));
        // the case holds no "late" value; "late" became mandatory after it opened; editing "note" still saves
        VisaField late = field("late", VisaFieldType.TEXT, true);
        given(visaFieldService.activeFor(anyString())).willReturn(List.of(nationalityNote, medical, late));
        assertThat(service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).values(map("note", "new")).build())
                .getValues()).containsEntry("note", "new");
    }

    @Test
    void aRemovedFieldCannotBeWrittenAndAPriceNeedsACurrency() {
        stored("T1", 0);
        assertThatThrownBy(() -> service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).values(map("gone", "x")).build()))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).purchasePrice(BigDecimal.ONE).build()))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("purchaseCurrency"));
    }

    @Test
    void anAgentCannotEditAnotherAgentsCase() {
        signIn("A1", UserType.AGENT);
        stored("A2", 0);
        assertThatThrownBy(() -> service.updateValues("VI1", VisaValuesUpdateRequest.builder().version(0L).build()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.uploadFile("VI1", "con", pdf)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.file("VI1", "con")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteFile("VI1", "con")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAgentWhoHandlesVisasOpensInTheirName() {
        signIn("A1", UserType.AGENT);
        given(agentRepository.findById("A1")).willReturn(Optional.of(Agent.builder().id("A1").name("Priya").manageableServices(List.of(ServiceType.VISA)).build()));
        assertThat(service.createVisa(open(map("med", "RECVD")), Map.of("con", pdf)).getAgentName()).isEqualTo("Priya");
    }

    @Test
    void uploadingReplacesTheOldFileAfterCommit() {
        stored("T1", 0);
        VisaFile old = VisaFile.builder().id("F0").visaId("VI1").fieldId("con").fileKey("key-old").fileName("old.pdf").build();
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "con")).willReturn(Optional.of(old));
        VisaFileResponse out = service.uploadFile("VI1", "con", pdf);
        assertThat(out.getFileName()).isEqualTo("contract.pdf");
        verify(visaFileRepository).delete(old);
        verify(fileStorage, never()).delete("key-old");
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(fileStorage).delete("key-old");
        verify(fileStorage, never()).delete("key-new");
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA), eq("VI1"), anyString(), anyList());
    }

    @Test
    void aFirstUploadRecordsNoPreviousFile() {
        stored("T1", 0);
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "pho")).willReturn(Optional.empty());
        assertThat(service.uploadFile("VI1", "pho", pdf).getFieldId()).isEqualTo("pho");
        ArgumentCaptor<List<AuditChange>> changes = ArgumentCaptor.forClass(List.class);
        verify(auditService).recordUpdate(eq(AuditEntityType.VISA), eq("VI1"), anyString(), changes.capture());
        assertThat(changes.getValue().get(0).getOldValue()).isNull();
        verify(visaFileRepository, never()).delete(any());
    }

    @Test
    void onlyAFileFieldOfTheCaseTakesAFileAndItMustHaveContent() {
        stored("T1", 0);
        assertThatThrownBy(() -> service.uploadFile("VI1", "med", pdf)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.uploadFile("VI1", "other", pdf)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.uploadFile("VI1", "con", null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.uploadFile("VI1", "con", new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0])))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("file"));
    }

    @Test
    void aMandatoryFileCannotBeDeletedAnOptionalOneCan() {
        stored("T1", 0);
        VisaFile con = VisaFile.builder().id("F1").visaId("VI1").fieldId("con").fileKey("k1").fileName("c.pdf").build();
        VisaFile pho = VisaFile.builder().id("F2").visaId("VI1").fieldId("pho").fileKey("k2").fileName("p.png").build();
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "con")).willReturn(Optional.of(con));
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "pho")).willReturn(Optional.of(pho));
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "none")).willReturn(Optional.empty());
        given(visaFieldService.byIds(List.of("con"))).willReturn(Map.of("con", contract));
        given(visaFieldService.byIds(List.of("pho"))).willReturn(Map.of("pho", photo));
        assertThatThrownBy(() -> service.deleteFile("VI1", "con")).isInstanceOf(FieldValidationException.class);
        service.deleteFile("VI1", "pho");
        verify(visaFileRepository).delete(pho);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(fileStorage).delete("k2");
        assertThatThrownBy(() -> service.deleteFile("VI1", "none")).isInstanceOf(NotFoundException.class);
        // a mandatory field that was since removed no longer protects its file
        VisaField removed = contract.toBuilder().active(false).build();
        given(visaFieldService.byIds(List.of("con"))).willReturn(Map.of("con", removed));
        service.deleteFile("VI1", "con");
        verify(visaFileRepository).delete(con);
    }

    @Test
    void aFileDownloadsOrIsNotFound() {
        stored("T1", 0);
        given(visaFileRepository.findByVisaIdAndFieldId("VI1", "con")).willReturn(Optional.of(
                VisaFile.builder().fieldId("con").fileKey("k1").fileName("c.pdf").fileType("application/pdf").build()));
        ByteArrayResource bytes = new ByteArrayResource(new byte[]{1});
        given(fileStorage.retrieve("k1")).willReturn(bytes);
        VisaService.StoredVisaFile f = service.file("VI1", "con");
        assertThat(f.resource()).isSameAs(bytes);
        assertThat(f.fileName()).isEqualTo("c.pdf");
        assertThat(f.contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> service.file("VI1", "pho")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theDetailNamesRemovedFieldsItStillHolds() {
        Visa v = stored("T1", 0);
        v.getFieldValues().put("gone", "x");
        given(visaFieldService.activeFor("Kenya")).willReturn(List.of(medical, nationalityNote));
        given(visaFileRepository.findByVisaId("VI1")).willReturn(List.of(VisaFile.builder().fieldId("oldfile").fileName("a.pdf").build()));
        given(visaFieldService.byIds(anyCollection())).willReturn(Map.of("gone", field("gone", VisaFieldType.TEXT, false),
                "oldfile", field("oldfile", VisaFieldType.FILE, false)));
        VisaResponse out = service.getVisa("VI1");
        assertThat(out.getRemovedFields()).containsOnly(Map.entry("gone", "GONE"), Map.entry("oldfile", "OLDFILE"));
        assertThat(out.getFiles()).hasSize(1);
    }

    @Test
    void listsCarryEachCasesFiles() {
        Visa a = Visa.builder().id("A").agentId("T1").clientName("x").country("Kenya").fieldValues(null).status(VisaStatus.DOCUMENTS_PENDING).build();
        Visa b = Visa.builder().id("B").agentId("T1").clientName("x").country("Kenya").status(VisaStatus.DOCUMENTS_PENDING).build();
        given(visaRepository.findAll()).willReturn(List.of(a, b));
        given(visaFileRepository.findByVisaIdIn(List.of("A", "B"))).willReturn(List.of(VisaFile.builder().visaId("A").fieldId("con").fileName("c.pdf").build()));
        List<VisaResponse> out = service.listVisas();
        assertThat(out.get(0).getFiles()).hasSize(1);
        assertThat(out.get(0).getValues()).isEmpty();
        assertThat(out.get(1).getFiles()).isEmpty();
        given(visaRepository.findAll()).willReturn(List.of());
        assertThat(service.listVisas()).isEmpty();
    }
}
