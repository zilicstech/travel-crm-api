-- Slice 6 supplier readiness: the guarded Tripjack live switch and the hotel pricing basket.

-- Off until a second platform admin approves another admin's request (platform_setting_request).
-- Saving a PROD Tripjack credential, or starting with a Tripjack booking provider, is refused while it is false.
INSERT INTO platform_setting (key, value) VALUES
  ('tripjack_live_ready', 'false'),
  -- room rates excl. taxes with the GST slab of each band (5% up to 7500 a night, 18% above)
  ('reference_fare_basket_hotel', '[{"label":"Budget 3*","gross":3150,"base":3000},{"label":"Mid 4*","gross":9440,"base":8000},{"label":"Premium 5*","gross":23600,"base":20000}]')
ON CONFLICT (key) DO NOTHING;

-- A setting change that needs a second platform admin: requested by one, approved (or withdrawn) by another.
CREATE TABLE platform_setting_request (
    id           VARCHAR(36)   PRIMARY KEY,
    setting_key  VARCHAR(60)   NOT NULL,
    value        VARCHAR(4000) NOT NULL,
    requested_by VARCHAR(36)   NOT NULL,
    requested_at TIMESTAMPTZ   NOT NULL,
    decided_by   VARCHAR(36),
    decided_at   TIMESTAMPTZ,
    outcome      VARCHAR(20)
);
-- one open request per setting
CREATE UNIQUE INDEX uq_platform_setting_request_open ON platform_setting_request (setting_key) WHERE decided_at IS NULL;
