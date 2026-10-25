-- A Tripjack statement file may be imported again (after an unmatched cause is fixed): the per-line partial unique index
-- ux_platform_statement_line_applied keeps each (booking, type) line applied once. The file hash stays recorded.
ALTER TABLE platform_statement_import DROP CONSTRAINT uq_platform_statement_import_sha;
