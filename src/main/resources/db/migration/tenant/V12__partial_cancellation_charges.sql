-- What the airline and supplier, and the agency, kept on earlier partial cancellations (client 27 Nov): still owed when
-- the rest of the booking is cancelled, so a full cancel adds them to its own charges.
ALTER TABLE booking ADD COLUMN partial_supplier_charge numeric(19,2) DEFAULT 0 NOT NULL;
ALTER TABLE booking ADD COLUMN partial_agency_fee numeric(19,2) DEFAULT 0 NOT NULL;
