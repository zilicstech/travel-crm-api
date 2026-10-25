package com.voyra.crm.security;

import com.voyra.crm.controller.AccountingChangeAlertController;
import com.voyra.crm.controller.AccountsDashboardController;
import com.voyra.crm.controller.AgencyProfileController;
import com.voyra.crm.controller.AgencySubscriptionController;
import com.voyra.crm.controller.AgencySettingController;
import com.voyra.crm.controller.AgentDashboardController;
import com.voyra.crm.controller.AuditLogController;
import com.voyra.crm.controller.AuthController;
import com.voyra.crm.controller.BankAccountController;
import com.voyra.crm.controller.BankMatchRuleController;
import com.voyra.crm.controller.BankStatementImportController;
import com.voyra.crm.controller.BankStatementProfileController;
import com.voyra.crm.controller.BankTransactionController;
import com.voyra.crm.controller.BookingController;
import com.voyra.crm.controller.BookingDocumentController;
import com.voyra.crm.controller.BookingLifecycleController;
import com.voyra.crm.controller.CalendarController;
import com.voyra.crm.controller.ClientController;
import com.voyra.crm.controller.ClientSubResourceController;
import com.voyra.crm.controller.CommunicationLogController;
import com.voyra.crm.controller.ControlAccountReconciliationController;
import com.voyra.crm.controller.CreditNoteController;
import com.voyra.crm.controller.CustomerLedgerController;
import com.voyra.crm.controller.EscalationController;
import com.voyra.crm.controller.ExchangeRateController;
import com.voyra.crm.controller.ExchangeRateLookupController;
import com.voyra.crm.controller.FeedbackController;
import com.voyra.crm.controller.FinancialStatementsController;
import com.voyra.crm.controller.FlightSearchController;
import com.voyra.crm.controller.FollowUpBoardController;
import com.voyra.crm.controller.GeneralLedgerController;
import com.voyra.crm.controller.HotelSearchController;
import com.voyra.crm.controller.InvoiceController;
import com.voyra.crm.controller.InvoiceDocumentController;
import com.voyra.crm.controller.JournalController;
import com.voyra.crm.controller.LeadController;
import com.voyra.crm.controller.LeadFollowUpController;
import com.voyra.crm.controller.LeadServiceController;
import com.voyra.crm.controller.MarkupDefaultController;
import com.voyra.crm.controller.OwnerAgentController;
import com.voyra.crm.controller.OwnerDashboardController;
import com.voyra.crm.controller.PartyLedgerController;
import com.voyra.crm.controller.PayablesDashboardController;
import com.voyra.crm.controller.PaymentReceiptController;
import com.voyra.crm.controller.PlatformAdminBookingController;
import com.voyra.crm.controller.PlatformAgencyApplicationController;
import com.voyra.crm.controller.PlatformAgencyController;
import com.voyra.crm.controller.PlatformAgencyPricingController;
import com.voyra.crm.controller.PlatformBookingController;
import com.voyra.crm.controller.PlatformInquiryController;
import com.voyra.crm.controller.PlatformAgencySubscriptionController;
import com.voyra.crm.controller.PlatformSettingController;
import com.voyra.crm.controller.PlatformSupplierCredentialController;
import com.voyra.crm.controller.PlatformTopupController;
import com.voyra.crm.controller.PlatformWalletController;
import com.voyra.crm.controller.PublicAgencyApplicationController;
import com.voyra.crm.controller.PublicFeedbackController;
import com.voyra.crm.controller.PublicInquiryController;
import com.voyra.crm.controller.PublicPaymentWebhookController;
import com.voyra.crm.controller.PublicProposalController;
import com.voyra.crm.controller.ReferenceDataController;
import com.voyra.crm.controller.ReportController;
import com.voyra.crm.controller.ServiceBoardController;
import com.voyra.crm.controller.ShiftHandoverController;
import com.voyra.crm.controller.SupplierCreditNoteController;
import com.voyra.crm.controller.SupplierInvoiceController;
import com.voyra.crm.controller.SupplierLedgerController;
import com.voyra.crm.controller.SupplierPaymentController;
import com.voyra.crm.controller.TaxConfigController;
import com.voyra.crm.controller.TopupController;
import com.voyra.crm.controller.VendorController;
import com.voyra.crm.controller.VisaController;
import com.voyra.crm.controller.WalletController;
import com.voyra.crm.enums.SkuRule;

import java.util.List;
import java.util.Map;

import static com.voyra.crm.enums.SkuRule.ACCOUNTS_GST;
import static com.voyra.crm.enums.SkuRule.CRM_CORE;
import static com.voyra.crm.enums.SkuRule.NEVER_GATED;
import static com.voyra.crm.enums.SkuRule.REPORTS;
import static com.voyra.crm.enums.SkuRule.STAFF_SEATS;
import static com.voyra.crm.enums.SkuRule.VISA;
import static java.util.Map.entry;

/**
 * Every controller class and the SKU it needs (spec §5.2 "Unlocks", §8). Explicit on purpose: a new
 * controller fails ControllerSkuTableContractTest and blueprint-audit.sh until it is added here.
 * Never gated = Starter: auth, public links, reference data, portal (search, platform bookings,
 * wallet), agency profile, owner dashboard, staff management, and the super-admin platform API.
 */
public final class ControllerSkuTable {

    /** Spec §8's never-gated paths: free in every subscription state, so READ_ONLY leaves them alone too. */
    public static final List<String> NEVER_GATED_PATHS = List.of(
            "/api/auth", "/api/public", "/api/reference", "/api/platform-bookings", "/api/wallet",
            "/api/flights", "/api/hotels", "/api/agency/profile", "/api/agency/subscription", "/api/platform");

    public static final Map<Class<?>, SkuRule> TABLE = Map.ofEntries(
            // Starter and platform
            entry(AuthController.class, NEVER_GATED),
            entry(PublicAgencyApplicationController.class, NEVER_GATED),
            entry(PublicFeedbackController.class, NEVER_GATED),
            entry(PublicInquiryController.class, NEVER_GATED),
            entry(PublicPaymentWebhookController.class, NEVER_GATED),
            entry(PublicProposalController.class, NEVER_GATED),
            entry(ReferenceDataController.class, NEVER_GATED),
            entry(FlightSearchController.class, NEVER_GATED),
            entry(HotelSearchController.class, NEVER_GATED),
            entry(PlatformBookingController.class, NEVER_GATED),
            entry(WalletController.class, NEVER_GATED),
            entry(TopupController.class, NEVER_GATED),
            entry(AgencyProfileController.class, NEVER_GATED),
            // a read-only owner must still see the plan and pay (Task 4 adds the payment routes here)
            entry(AgencySubscriptionController.class, NEVER_GATED),
            entry(PlatformAgencySubscriptionController.class, NEVER_GATED),
            entry(OwnerDashboardController.class, NEVER_GATED),
            entry(OwnerAgentController.class, NEVER_GATED),
            entry(PlatformAdminBookingController.class, NEVER_GATED),
            entry(PlatformAgencyApplicationController.class, NEVER_GATED),
            entry(PlatformAgencyController.class, NEVER_GATED),
            entry(PlatformAgencyPricingController.class, NEVER_GATED),
            entry(PlatformInquiryController.class, NEVER_GATED),
            entry(PlatformSettingController.class, NEVER_GATED),
            entry(PlatformSupplierCredentialController.class, NEVER_GATED),
            entry(PlatformTopupController.class, NEVER_GATED),
            entry(PlatformWalletController.class, NEVER_GATED),
            entry(ExchangeRateController.class, NEVER_GATED),
            // CRM Core
            entry(LeadController.class, CRM_CORE),
            entry(LeadServiceController.class, CRM_CORE),
            entry(LeadFollowUpController.class, CRM_CORE),
            entry(ServiceBoardController.class, CRM_CORE),
            entry(FollowUpBoardController.class, CRM_CORE),
            entry(CalendarController.class, CRM_CORE),
            entry(ShiftHandoverController.class, CRM_CORE),
            entry(EscalationController.class, CRM_CORE),
            entry(ClientController.class, CRM_CORE),
            entry(ClientSubResourceController.class, CRM_CORE),
            entry(CommunicationLogController.class, CRM_CORE),
            entry(FeedbackController.class, CRM_CORE),
            entry(BookingController.class, CRM_CORE),
            entry(BookingDocumentController.class, CRM_CORE),
            entry(BookingLifecycleController.class, CRM_CORE),
            entry(VendorController.class, CRM_CORE),
            entry(InvoiceController.class, CRM_CORE),
            entry(AgencySettingController.class, CRM_CORE),
            entry(MarkupDefaultController.class, CRM_CORE),
            entry(AuditLogController.class, CRM_CORE),
            // client invoicing, its receipts, and the tax config it reads (spec §5.2 "client invoices")
            entry(InvoiceDocumentController.class, CRM_CORE),
            entry(PaymentReceiptController.class, CRM_CORE),
            entry(TaxConfigController.class, CRM_CORE),
            // Staff seats (staff management itself is never gated: the seat check gates create/reactivate)
            entry(AgentDashboardController.class, STAFF_SEATS),
            // Accounts & GST
            entry(AccountsDashboardController.class, ACCOUNTS_GST),
            entry(AccountingChangeAlertController.class, ACCOUNTS_GST),
            entry(CreditNoteController.class, ACCOUNTS_GST),
            entry(CustomerLedgerController.class, ACCOUNTS_GST),
            entry(PartyLedgerController.class, ACCOUNTS_GST),
            entry(GeneralLedgerController.class, ACCOUNTS_GST),
            entry(JournalController.class, ACCOUNTS_GST),
            entry(FinancialStatementsController.class, ACCOUNTS_GST),
            entry(ControlAccountReconciliationController.class, ACCOUNTS_GST),
            entry(ExchangeRateLookupController.class, ACCOUNTS_GST),
            entry(PayablesDashboardController.class, ACCOUNTS_GST),
            entry(SupplierInvoiceController.class, ACCOUNTS_GST),
            entry(SupplierPaymentController.class, ACCOUNTS_GST),
            entry(SupplierCreditNoteController.class, ACCOUNTS_GST),
            entry(SupplierLedgerController.class, ACCOUNTS_GST),
            entry(BankAccountController.class, ACCOUNTS_GST),
            entry(BankTransactionController.class, ACCOUNTS_GST),
            entry(BankStatementImportController.class, ACCOUNTS_GST),
            entry(BankStatementProfileController.class, ACCOUNTS_GST),
            entry(BankMatchRuleController.class, ACCOUNTS_GST),
            // Reports, Visa desk
            entry(ReportController.class, REPORTS),
            entry(VisaController.class, VISA));

    private ControllerSkuTable() {
    }

    public static boolean isNeverGatedPath(String path) {
        return NEVER_GATED_PATHS.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /** Fails closed: an unclassified controller is a build failure, never a silently open route. */
    public static SkuRule rule(Class<?> controller) {
        SkuRule rule = TABLE.get(controller);
        if (rule == null) {
            throw new IllegalStateException(controller.getSimpleName() + " is not classified in ControllerSkuTable");
        }
        return rule;
    }
}
