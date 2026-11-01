-- Plan prices (2026-11-01, spec Part 3). Expand only; V39 (next release) drops what this stops reading.
-- DEFAULT 0: an old instance still creates Basic agencies without these columns during the rolling deploy.
ALTER TABLE subscription
    ADD COLUMN plan_price_monthly NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (plan_price_monthly >= 0),
    ADD COLUMN seat_price_monthly NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (seat_price_monthly >= 0);

DO $$
DECLARE
    p JSONB := COALESCE((SELECT value::jsonb FROM platform_setting WHERE key = 'sku_prices'),
        '{"CRM_CORE":2499,"STAFF_SEATS":399,"ACCOUNTS_GST":1999,"REPORTS":699,"VISA":499,"SUITE":4999,"LITE":2999}'::jsonb);
    seat NUMERIC := (p ->> 'STAFF_SEATS')::numeric;
    r RECORD; target TEXT; included INT; price NUMERIC;
BEGIN
    UPDATE subscription SET seat_price_monthly = seat,
        plan_price_monthly = CASE plan WHEN 'LITE' THEN (p ->> 'LITE')::numeric
                                       WHEN 'SUITE' THEN (p ->> 'SUITE')::numeric ELSE 0 END
    WHERE plan <> 'CUSTOM';
    FOR r IN SELECT tenant_id, plan FROM subscription WHERE plan IN ('LITE','SUITE') AND plan_price_monthly = 0 LOOP
        RAISE WARNING 'V38: agency % on % has a zero plan price (sku_prices % was 0)', r.tenant_id, r.plan, r.plan;
    END LOOP;
    FOR r IN SELECT id, tenant_id, skus, seats FROM subscription WHERE plan = 'CUSTOM' FOR UPDATE LOOP
        target := CASE WHEN 'ACCOUNTS_GST' = ANY (r.skus) THEN 'SUITE' ELSE 'LITE' END;
        included := CASE target WHEN 'SUITE' THEN 5 ELSE 3 END;
        -- today's renewal, unchanged: the non-seat SKUs plus the seats the plan now includes
        price := COALESCE((SELECT SUM((p ->> s)::numeric) FROM (SELECT DISTINCT unnest(r.skus) AS s) u WHERE s <> 'STAFF_SEATS' AND p ? s), 0)
               + CASE WHEN 'STAFF_SEATS' = ANY (r.skus) THEN LEAST(r.seats, included) * seat ELSE 0 END;
        UPDATE subscription SET plan = target,
            seats = CASE WHEN 'STAFF_SEATS' = ANY (r.skus) THEN GREATEST(r.seats - included, 0) ELSE 0 END,
            skus = CASE target WHEN 'SUITE' THEN ARRAY['CRM_CORE','STAFF_SEATS','ACCOUNTS_GST','REPORTS','VISA']
                               ELSE ARRAY['CRM_CORE','STAFF_SEATS','REPORTS','VISA'] END,
            plan_price_monthly = price, seat_price_monthly = seat, updated_at = now()
        WHERE id = r.id;
        IF price = 0 THEN
            RAISE WARNING 'V38: agency % converted from CUSTOM to % at a zero plan price, it will not be billed', r.tenant_id, target;
        END IF;
        RAISE NOTICE 'V38: agency % moved from CUSTOM % (% seats) to % at % a month', r.tenant_id, r.skus, r.seats, target, price;
    END LOOP;
END $$;

ALTER TABLE subscription DROP CONSTRAINT subscription_plan_check;
ALTER TABLE subscription ADD CONSTRAINT subscription_plan_check CHECK (plan IN ('STARTER','LITE','SUITE'));
