-- Leads created without a source were stored as the raw code PHONE_CALL; the agency's lead
-- source list (AgencySettingDefaults) names it "Phone Call", so reports showed both.
UPDATE lead SET source = 'Phone Call' WHERE source = 'PHONE_CALL';
