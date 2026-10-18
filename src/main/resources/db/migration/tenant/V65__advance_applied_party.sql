-- BUG-MO-09: advance-applied journals debited 2110 (Client Advances Held) without naming the
-- client, so a per-client 2110 balance never netted what was applied. The client is the party on
-- the same entry's 1200 line; reversals carry the same purpose and lines, so they are covered too.
UPDATE journal_line l
SET party_type = ar.party_type,
    party_id   = ar.party_id
FROM journal_entry e, journal_line ar
WHERE l.journal_entry_id = e.id
  AND e.purpose = 'ADVANCE_APPLIED'
  AND l.account_code = '2110'
  AND l.party_id IS NULL
  AND ar.journal_entry_id = e.id
  AND ar.account_code = '1200'
  AND ar.party_id IS NOT NULL;
