package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.ClientInvoiceResponse;
import com.voyra.crm.dto.InvoiceSummaryResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.dto.SupplierInvoiceCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceResponse;
import com.voyra.crm.dto.SupplierInvoiceStatusUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.SupplierInvoice;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadTimelineEventType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.LeadServiceRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.AuditSnapshot;
import com.voyra.crm.util.LeadAccessChecker;
import com.voyra.crm.util.UniqueIdResolver;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private static final BigDecimal DEFAULT_GST_RATE = new BigDecimal("18.00");
    private static final String[] CLIENT_INVOICE_AUDITED = {
            "description", "amount", "gst", "totalWithGst", "amountPaid", "status", "dueDate", "paymentMode"
    };
    private static final String[] SUPPLIER_INVOICE_AUDITED = {
            "supplierName", "category", "amount", "status", "dueDate", "bookingRef"
    };

    private final ClientInvoiceRepository clientInvoiceRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final ClientRepository clientRepository;
    private final AgentRepository agentRepository;
    private final LeadRepository leadRepository;
    private final LeadServiceRepository leadServiceRepository;
    private final LeadTimelineService leadTimelineService;
    private final AuditService auditService;

    @Transactional
    public ClientInvoiceResponse createClientInvoice(ClientInvoiceCreateRequest request) {
        String agentId = resolveOwningAgentId(request.getAgentId());
        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + request.getClientId()));

        String serviceLabel = null;
        if (request.getLeadId() != null) {
            assertLeadAccessible(request.getLeadId());
            if (request.getServiceId() != null) {
                serviceLabel = leadServiceRepository.findById(request.getServiceId())
                        .orElseThrow(() -> new IllegalArgumentException("Service not found: " + request.getServiceId()))
                        .getLabel();
            }
        }

        BigDecimal gstRate = request.getGstRate() != null ? request.getGstRate() : DEFAULT_GST_RATE;
        BigDecimal amount = request.getAmount();
        BigDecimal gst = amount.multiply(gstRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal totalWithGst = amount.add(gst);

        ClientInvoice invoice = ClientInvoice.builder()
                .id(generateUniqueId(clientInvoiceRepository::existsById))
                .clientId(client.getId())
                .clientName(client.getName())
                .agentId(agentId)
                .leadId(request.getLeadId())
                .serviceId(request.getServiceId())
                .serviceLabel(serviceLabel)
                .description(request.getDescription())
                .amount(amount)
                .gst(gst)
                .totalWithGst(totalWithGst)
                .amountPaid(BigDecimal.ZERO)
                .status(InvoiceStatus.PENDING)
                .invoiceDate(LocalDate.now())
                .dueDate(request.getDueDate())
                .paymentMode(request.getPaymentMode())
                .createdBy(agentId)
                .build();
        clientInvoiceRepository.save(invoice);
        auditService.recordCreate(AuditEntityType.CLIENT_INVOICE, invoice.getId(), labelFor(invoice));

        if (request.getLeadId() != null) {
            leadTimelineService.record(request.getLeadId(), request.getServiceId(), LeadTimelineEventType.INVOICE_ADDED,
                    "Invoice raised" + (request.getDescription() != null ? ": " + request.getDescription() : ""));
        }
        log.info("Client invoice created: invoiceId={}, clientId={}", invoice.getId(), client.getId());
        return toClientResponse(invoice);
    }

    @Transactional(readOnly = true)
    public List<ClientInvoiceResponse> listForLead(String leadId) {
        return clientInvoiceRepository.findByLeadIdOrderByInvoiceDateDesc(leadId).stream()
                .map(this::toClientResponse).toList();
    }

    private void assertLeadAccessible(String leadId) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new IllegalArgumentException("Lead not found: " + leadId));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !lead.getCreatedBy().equals(principal.userId())
                && !LeadAccessChecker.hasServiceAccess(leadServiceRepository, agentRepository, leadId, principal.userId())) {
            throw new AccessDeniedException("This lead is not accessible to you");
        }
    }

    @Transactional(readOnly = true)
    public List<ClientInvoiceResponse> listClientInvoices() {
        return scopedClientInvoices().stream().map(this::toClientResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<ClientInvoiceResponse> listClientInvoices(Pageable pageable) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        Page<ClientInvoice> page = principal.isAgent()
                ? clientInvoiceRepository.findByAgentId(principal.userId(), pageable)
                : clientInvoiceRepository.findAll(pageable);
        return PagedResponse.from(page, this::toClientResponse);
    }

    @Transactional
    public ClientInvoiceResponse recordPayment(String id, ClientInvoicePaymentRequest request) {
        ClientInvoice invoice = clientInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + id));
        Map<String, String> before = AuditSnapshot.of(invoice, CLIENT_INVOICE_AUDITED);
        invoice.setAmountPaid(request.getAmountPaid());
        if (request.getPaymentMode() != null) {
            invoice.setPaymentMode(request.getPaymentMode());
        }
        if (request.getAmountPaid().compareTo(invoice.getTotalWithGst()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else if (request.getAmountPaid().compareTo(BigDecimal.ZERO) > 0) {
            invoice.setStatus(InvoiceStatus.PARTIAL);
        } else {
            invoice.setStatus(InvoiceStatus.PENDING);
        }
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(invoice, CLIENT_INVOICE_AUDITED));
        touch(invoice);
        clientInvoiceRepository.save(invoice);
        auditService.recordUpdate(AuditEntityType.CLIENT_INVOICE, invoice.getId(), labelFor(invoice), changes);
        log.info("Client invoice payment recorded: invoiceId={}, amountPaid={}", id, request.getAmountPaid());
        return toClientResponse(invoice);
    }

    @Transactional
    public SupplierInvoiceResponse createSupplierInvoice(SupplierInvoiceCreateRequest request) {
        SupplierInvoice invoice = SupplierInvoice.builder()
                .id(generateUniqueId(supplierInvoiceRepository::existsById))
                .supplierName(request.getSupplierName())
                .category(request.getCategory())
                .amount(request.getAmount())
                .status(InvoiceStatus.PENDING)
                .dueDate(request.getDueDate())
                .bookingRef(request.getBookingRef())
                .createdBy(SecurityContextUtil.getCurrentUserOrThrow().userId())
                .build();
        supplierInvoiceRepository.save(invoice);
        auditService.recordCreate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), invoice.getSupplierName());
        log.info("Supplier invoice created: invoiceId={}", invoice.getId());
        return toSupplierResponse(invoice);
    }

    @Transactional(readOnly = true)
    public List<SupplierInvoiceResponse> listSupplierInvoices() {
        return supplierInvoiceRepository.findAll().stream().map(this::toSupplierResponse).toList();
    }

    @Transactional
    public SupplierInvoiceResponse updateSupplierInvoiceStatus(String id, SupplierInvoiceStatusUpdateRequest request) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Supplier invoice not found: " + id));
        Map<String, String> before = AuditSnapshot.of(invoice, SUPPLIER_INVOICE_AUDITED);
        invoice.setStatus(request.getStatus());
        List<AuditChange> changes = AuditSnapshot.diff(before, AuditSnapshot.of(invoice, SUPPLIER_INVOICE_AUDITED));
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
        supplierInvoiceRepository.save(invoice);
        auditService.recordUpdate(AuditEntityType.SUPPLIER_INVOICE, invoice.getId(), invoice.getSupplierName(), changes);
        return toSupplierResponse(invoice);
    }

    private void touch(ClientInvoice invoice) {
        invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setUpdatedBy(SecurityContextUtil.getCurrentUserOrThrow().userId());
    }

    private String labelFor(ClientInvoice i) {
        return i.getClientName() + " / " + (i.getDescription() != null ? i.getDescription() : i.getId());
    }

    /**
     * Owner sees agency-wide client + supplier figures. Agent sees only their own client
     * invoices, and zero supplier figures - payables are not agent-scoped data. Mixing the
     * two scopes in one response would report agency-wide payables against an agent's own
     * receivables.
     */
    @Transactional(readOnly = true)
    public InvoiceSummaryResponse getSummary() {
        List<ClientInvoice> clientInvoices = scopedClientInvoices();
        // Supplier invoices are accounts-payable data: Owner-only, matching the three
        // supplier endpoints. An Agent's summary reports on their own client invoices only.
        boolean isOwner = !SecurityContextUtil.getCurrentUserOrThrow().isAgent();
        List<SupplierInvoice> supplierInvoices = isOwner ? supplierInvoiceRepository.findAll() : List.of();

        BigDecimal totalCollected = sum(clientInvoices, ClientInvoice::getAmountPaid);
        BigDecimal totalPendingToCollect = clientInvoices.stream()
                .map(i -> i.getTotalWithGst().subtract(i.getAmountPaid()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGst = sum(clientInvoices, ClientInvoice::getGst);
        BigDecimal totalPaidToSuppliers = supplierInvoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.PAID)
                .map(SupplierInvoice::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPendingToPay = supplierInvoices.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                .map(SupplierInvoice::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return InvoiceSummaryResponse.builder()
                .totalCollected(totalCollected)
                .totalPendingToCollect(totalPendingToCollect)
                .totalGst(totalGst)
                .totalPaidToSuppliers(totalPaidToSuppliers)
                .totalPendingToPay(totalPendingToPay)
                .build();
    }

    private List<ClientInvoice> scopedClientInvoices() {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        return principal.isAgent()
                ? clientInvoiceRepository.findByAgentId(principal.userId())
                : clientInvoiceRepository.findAll();
    }

    private String resolveOwningAgentId(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            return principal.userId();
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a client invoice");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return agent.getId();
    }

    private BigDecimal sum(List<ClientInvoice> invoices, java.util.function.Function<ClientInvoice, BigDecimal> extractor) {
        return invoices.stream().map(extractor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isOverdue(LocalDate dueDate, BigDecimal pendingAmount) {
        return dueDate != null && dueDate.isBefore(LocalDate.now()) && pendingAmount.compareTo(BigDecimal.ZERO) > 0;
    }

    private boolean isOverdue(LocalDate dueDate, InvoiceStatus status) {
        return dueDate != null && dueDate.isBefore(LocalDate.now()) && status != InvoiceStatus.PAID;
    }

    private ClientInvoiceResponse toClientResponse(ClientInvoice i) {
        BigDecimal pending = i.getTotalWithGst().subtract(i.getAmountPaid());
        return ClientInvoiceResponse.builder()
                .id(i.getId()).clientId(i.getClientId()).clientName(i.getClientName())
                .agentId(i.getAgentId()).leadId(i.getLeadId()).serviceId(i.getServiceId())
                .serviceLabel(i.getServiceLabel()).description(i.getDescription())
                .amount(i.getAmount()).gst(i.getGst()).totalWithGst(i.getTotalWithGst())
                .amountPaid(i.getAmountPaid()).pending(pending).status(i.getStatus())
                .invoiceDate(i.getInvoiceDate()).dueDate(i.getDueDate()).paymentMode(i.getPaymentMode())
                .overdue(isOverdue(i.getDueDate(), pending))
                .build();
    }

    private SupplierInvoiceResponse toSupplierResponse(SupplierInvoice i) {
        return SupplierInvoiceResponse.builder()
                .id(i.getId()).supplierName(i.getSupplierName()).category(i.getCategory()).amount(i.getAmount())
                .status(i.getStatus()).dueDate(i.getDueDate()).bookingRef(i.getBookingRef())
                .overdue(isOverdue(i.getDueDate(), i.getStatus()))
                .build();
    }

    private String generateUniqueId(java.util.function.Predicate<String> existsById) {
        return UniqueIdResolver.resolve(existsById);
    }
}
