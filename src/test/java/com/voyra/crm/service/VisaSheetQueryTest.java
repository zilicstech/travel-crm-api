package com.voyra.crm.service;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.entity.VisaFile;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.enums.VisaStatus;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.models.VisaListFilter;
import com.voyra.crm.repository.VisaFileRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.repository.spec.CriteriaProbe;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisaSheetQueryTest {

    @Mock VisaRepository visaRepository;
    @Mock VisaFieldService visaFieldService;
    @Mock VisaFileRepository visaFileRepository;
    @Mock AuditService auditService;
    @Mock com.voyra.crm.repository.ClientRepository clientRepository;
    @Mock com.voyra.crm.repository.AgentRepository agentRepository;
    @Mock com.voyra.crm.repository.TenantRepository tenantRepository;
    @Mock com.voyra.crm.repository.MemberRepository memberRepository;
    @Mock com.voyra.crm.repository.VendorRepository vendorRepository;
    @Mock FileStorageService fileStorage;
    @Mock AuthorResolver authorResolver;
    @InjectMocks VisaService service;

    private final VisaField med = VisaField.builder().id("med").scope(VisaFieldScope.COUNTRY).country("Kenya").label("MEDICAL")
            .type(VisaFieldType.DROPDOWN).options(List.of("RECVD", "PENDING")).active(true).build();
    private final VisaField ok = VisaField.builder().id("ok").scope(VisaFieldScope.COMMON).label("=SUM(A1)").type(VisaFieldType.YES_NO)
            .options(List.of()).active(true).build();
    private final VisaField con = VisaField.builder().id("con").scope(VisaFieldScope.COUNTRY).country("Kenya").label("CONTRACT")
            .type(VisaFieldType.FILE).options(List.of()).active(true).build();
    private final VisaListFilter kenya = new VisaListFilter(null, "Kenya", null, null, null, null, Map.of("med", "PENDING"));

    @BeforeEach
    void setUp() {
        signIn("T1", UserType.AGENCY_OWNER);
        given(visaFieldService.activeFor("Kenya")).willReturn(List.of(ok, med, con));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String id, UserType type) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal(id, "user", type, "T1"), null, List.of()));
    }

    @Test
    void theDefaultOrderIsNewestCaseFirstAndABuiltInSortKeepsCaseOrderAfter() {
        assertThat(service.query(kenya, null).sort()).isEqualTo(Sort.by(Sort.Direction.DESC, "caseNo"));
        assertThat(service.query(kenya, " ").sort()).isEqualTo(Sort.by(Sort.Direction.DESC, "caseNo"));
        assertThat(service.query(kenya, "caseNo").sort()).isEqualTo(Sort.by(Sort.Direction.ASC, "caseNo"));
        assertThat(service.query(kenya, "memberName, DESC").sort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "memberName").and(Sort.by(Sort.Direction.DESC, "caseNo")));
        assertThat(service.query(kenya, "passportExpiry,asc").sort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "passportExpiry").and(Sort.by(Sort.Direction.DESC, "caseNo")));
        assertThat(service.query(kenya, null).fields()).containsExactly(ok, med, con);
    }

    @Test
    void aFieldSortIsInTheQueryAndAnUnknownOneIsRefused() {
        VisaService.VisaQuery q = service.query(kenya, "field.med,desc");
        assertThat(q.sort().isUnsorted()).isTrue();
        CriteriaProbe probe = new CriteriaProbe();
        probe.render(q.spec());
        assertThat(probe.calls()).anyMatch(c -> c.startsWith("query.orderBy("));
        for (String bad : List.of("field.gone", "field.con", "password", "field.")) {
            assertThatThrownBy(() -> service.query(kenya, bad))
                    .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("sort"));
        }
    }

    @Test
    void anAgentIsScopedToTheirCases() {
        signIn("A1", UserType.AGENT);
        CriteriaProbe probe = new CriteriaProbe();
        assertThat(probe.render(service.query(kenya, null).spec())).contains("equal(root.agentId, A1)");
        signIn("T1", UserType.AGENCY_OWNER);
        assertThat(new CriteriaProbe().render(service.query(kenya, null).spec())).doesNotContain("root.agentId");
    }

    @Test
    void aPageCarriesItsFilesAndTheChosenOrder() {
        Visa v = Visa.builder().id("V1").clientName("Asha").country("Kenya").status(VisaStatus.SUBMITTED).build();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        given(visaRepository.findAll(any(Specification.class), pageable.capture())).willAnswer(inv -> new PageImpl<>(List.of(v), inv.getArgument(1), 1));
        given(visaFileRepository.findByVisaIdIn(List.of("V1"))).willReturn(List.of(VisaFile.builder().visaId("V1").fieldId("con").fileName("c.pdf").build()));
        var page = service.searchVisas(kenya, "clientName", PageRequest.of(2, 10));
        assertThat(page.getContent()).singleElement().satisfies(r -> assertThat(r.getFiles()).hasSize(1));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().getOrderFor("clientName")).isNotNull();
    }

    @Test
    void theExportIsTheSameQueryAsCsvWithFormulasDisarmedAndAudited() {
        VisaExportService export = new VisaExportService(service, visaRepository, auditService);
        Visa v = Visa.builder().id("V1").caseNo(7L).createdDate(LocalDateTime.of(2026, 11, 24, 9, 5)).clientName("=cmd|' /C calc'!A0")
                .memberName("Ravi").passportNumber("Z1").passportExpiry(LocalDate.of(2031, 1, 1)).nationality("Indian").agentName("Dana")
                .status(VisaStatus.DOCUMENTS_PENDING).country("Kenya").visaType("Tourist")
                .fieldValues(new HashMap<>(Map.of("med", "PENDING", "ok", "true"))).purchasePrice(new BigDecimal("120.00")).purchaseCurrency("USD")
                .sellingPrice(new BigDecimal("12500.50")).vendorName("Atlas").remarks("+91 call").build();
        Visa bare = Visa.builder().id("V2").caseNo(8L).clientName("B").status(VisaStatus.APPROVED).country("Kenya").visaType("Work")
                .fieldValues(new HashMap<>(Map.of("ok", "false"))).build();
        given(visaRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(new PageImpl<>(List.of(v, bare)));
        given(visaFileRepository.findByVisaIdIn(List.of("V1", "V2"))).willReturn(List.of(VisaFile.builder().visaId("V1").fieldId("con").fileName("c.pdf").build()));

        VisaExportService.Export out = export.export(new VisaListFilter("ravi", "Kenya", VisaStatus.DOCUMENTS_PENDING, "A1",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Map.of("med", "PENDING")), null);

        String[] lines = out.csv().split("\n");
        assertThat(lines[0]).isEqualTo("No.,Opened,Client,Traveller,Passport no.,Passport expiry,Nationality,Agent,Status,Country,Visa type,"
                + "'=SUM(A1),MEDICAL,CONTRACT,Purchase,Purchase currency,Selling,Supplier,Remarks");
        assertThat(lines[1]).isEqualTo("7,2026-11-24 09:05,'=cmd|' /C calc'!A0,Ravi,Z1,2031-01-01,Indian,Dana,Documents pending,Kenya,Tourist,"
                + "Yes,PENDING,c.pdf,120.00,USD,12500.50,Atlas,'+91 call");
        assertThat(lines[2]).isEqualTo("8,,B,,,,,,Approved,Kenya,Work,No,,,,,,,");
        assertThat(out.rows()).isEqualTo(2);
        assertThat(out.truncated()).isFalse();
        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordReveal(eq(AuditEntityType.VISA), eq("export"), label.capture());
        assertThat(label.getValue()).isEqualTo("Visa sheet exported: 2 rows (country=Kenya, status=DOCUMENTS_PENDING, agent=A1, from=2026-01-01, "
                + "to=2026-12-31, search=yes, field filters=1)").doesNotContain("ravi");
    }

    @Test
    void theExportStopsAt5000Rows() {
        VisaExportService export = new VisaExportService(service, visaRepository, auditService);
        List<Visa> many = new ArrayList<>();
        IntStream.range(0, 5001).forEach(i -> many.add(Visa.builder().id("V" + i).caseNo((long) i).clientName("c").status(VisaStatus.SUBMITTED)
                .country("Kenya").visaType("T").fieldValues(new HashMap<>()).build()));
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        given(visaRepository.findAll(any(Specification.class), pageable.capture())).willReturn(new PageImpl<>(many));
        VisaExportService.Export out = export.export(new VisaListFilter(null, " ", null, null, null, null, null), null);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5001);
        assertThat(out.rows()).isEqualTo(5000);
        assertThat(out.truncated()).isTrue();
        assertThat(out.csv().split("\n")).hasSize(5001);
        verify(auditService).recordReveal(AuditEntityType.VISA, "export", "Visa sheet exported: 5000 rows");
    }

    @Test
    void safeLeavesOrdinaryAndEmptyCellsAlone() {
        assertThat(VisaExportService.safe("")).isEmpty();
        assertThat(VisaExportService.safe("Ravi")).isEqualTo("Ravi");
        assertThat(VisaExportService.safe("@x")).isEqualTo("'@x");
        assertThat(VisaExportService.safe("-1")).isEqualTo("'-1");
    }
}
