# Soft Delete Everywhere – Plan (approved and implemented 2026-10-18)

**Goal:** No user action removes a row or a stored file. A delete only marks the record as deleted, so it disappears from every screen and query while staying in the database for audit and recovery.

**Branch:** `feat/soft-delete-and-accounts` (both repos, cut from main after the bookings PRs merged).

## Approach
- **Columns.** Add `deleted_at TIMESTAMP` to every table a user can delete from, in tenant migration `V55__soft_delete.sql`, and to the public `agent` table in `V11__agent_soft_delete.sql`.
- **Entities.** Put Hibernate `@SQLDelete(sql = "UPDATE <table> SET deleted_at = now() WHERE id = ?")` and `@SQLRestriction("deleted_at IS NULL")` on each entity.
  - The existing `repository.delete(...)` and derived `deleteByXxx(...)` calls then soft-delete with no change to the services.
  - Every JPA read hides deleted rows automatically.
- **JPQL bulk deletes.** The `@Query("DELETE FROM ...")` calls bypass `@SQLDelete`. Rewrite them as `UPDATE ... SET deletedAt = now()`. That covers invoice line items and supplier invoice line items.
- **Native SQL reads.** These also bypass `@SQLRestriction`. Each native query that touches these tables, mainly reports and journals, gets `deleted_at IS NULL`, checked with a grep of `nativeQuery`.
- **Unique indexes.** Unique indexes on these tables become partial (`WHERE deleted_at IS NULL`), so a deleted record never blocks a new one with the same key. Examples: a supplier bill number, the one-live-invoice-per-booking index, agency settings.
- **Files.** Stop deleting files from GCS or local storage on a record delete or a file replace. The file stays and only the row is marked. `FileStorageService.delete` is no longer called from services.
- **`deleted_by` dropped.** A custom `@SQLDelete` statement cannot see the current user; who deleted what is in the audit log and the app logs.
- **Agent email.** The unique email index is narrowed to live rows, so a removed agent's email can be reused.

## Tables covered (today's hard deletes)

| Record | Delete path |
|---|---|
| Booking (pending only) + its documents, passengers, sectors, cost components | `BookingService.deleteBooking` |
| Booking voucher / document | `BookingDocumentService.deleteDocument` |
| Draft client invoice + lines + taxes | `InvoiceDocumentService` delete |
| Draft supplier bill + lines | `SupplierInvoiceService` delete |
| Lead proposal item | `LeadService` |
| Lead service | `ServiceInstanceService` |
| Lead follow-up | `LeadFollowUpService` |
| Communication log entry | `CommunicationLogService` |
| Member document | `MemberService` |
| Agency setting (dropdown values) | `AgencySettingService` |
| Agent (removal) | `AgentService.removeAgent`. This also keeps the login blocked: removed agents can't sign in. |

**Replace-on-edit rows:** booking passengers, sectors and cost components are rewritten when a booking is edited. These become soft-deleted too, which leaves a history of each edit.

**Already soft:** vendors (deactivate), clients and members (`is_active`), and cancelled invoices and bills (status). These are unchanged.

## Not in scope
- **Restore / "trash" screen.** Records can be recovered in the database. A UI can come later if wanted.
- **Physical purge job.**
- **Platform-level (public schema) tables.** Agencies are already deactivated, not deleted.

## Verification
- **New test:** after a delete, `findById` is empty, the native count still sees the row, and the stored file is not deleted.
- `mvn verify`, `tsc`, e2e booking suite, then regression-tester pre-merge.
