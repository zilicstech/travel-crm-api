-- Found via Chrome-driven QA of the accounting redesign: issuing an
-- AIR_INTERNATIONAL invoice failed with "value too long for type character
-- varying(20)". The per-category DocumentKind values added alongside
-- InvoiceServiceCategory (AIR_INTERNATIONAL_INVOICE, MISCELLANEOUS_INVOICE,
-- ...) run up to 25 characters; document_kind was sized for the original,
-- shorter five (TAX_INVOICE, PROFORMA, RECEIPT, CREDIT_NOTE,
-- PAYMENT_VOUCHER). Widened with headroom for anything reasonably added
-- later rather than exactly matching today's longest value.
ALTER TABLE document_number_sequence ALTER COLUMN document_kind TYPE VARCHAR(40);
