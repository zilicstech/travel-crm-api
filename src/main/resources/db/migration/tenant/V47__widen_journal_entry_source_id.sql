-- Decision 3.5 (unrealized forex revaluation, Rule 3.5.1-3.5.2): each month posts its own
-- revaluation entry, auto-reversed before the next month's runs. The reversed entry still
-- occupies its (source_type, source_id, purpose) slot in uq_journal_entry_source - that index
-- has no status filter - so a plain source_id = <bill id> collides across months. The fix is a
-- period-qualified source_id (<bill id>@<yyyyMM>, 43 chars for a 36-char UUID), which overflows
-- the original VARCHAR(36). Widening only, no data touched, every existing row already fits.

ALTER TABLE journal_entry ALTER COLUMN source_id TYPE VARCHAR(50);
