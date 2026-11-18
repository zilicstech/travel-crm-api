-- Local copy of each hotel supplier's hotel list (id, name, city, stars, type) so agents can search a hotel by name:
-- Tripjack's listing takes hotel ids only. Loaded and refreshed only by a platform admin's
-- POST /api/platform/hotel-catalog/{supplierId}/refresh; nothing loads on a schedule. No personal data.

-- Until now only TenantFlywayMigrator created pg_trgm, which a fresh database reaches after the public chain.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE TABLE public.hotel_catalog (
    supplier_id   character varying(36) NOT NULL REFERENCES public.platform_supplier_credential (id),
    hotel_id      text NOT NULL,
    name          text NOT NULL,
    city          text NOT NULL,
    country_code  text,
    country_name  text,
    star_rating   smallint CHECK (star_rating BETWEEN 1 AND 5),
    property_type text,
    seen_at       timestamptz NOT NULL,
    PRIMARY KEY (supplier_id, hotel_id)
);

CREATE INDEX idx_hotel_catalog_name_trgm ON public.hotel_catalog USING gin (name public.gin_trgm_ops);

-- One row per supplier: who is loading (run_id), whether it is alive (heartbeat_at) and how the last load ended.
CREATE TABLE public.hotel_catalog_sync (
    supplier_id  character varying(36) PRIMARY KEY REFERENCES public.platform_supplier_credential (id),
    status       character varying(10) NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    run_id       uuid,
    started_at   timestamptz,
    heartbeat_at timestamptz,
    finished_at  timestamptz,
    last_done_at timestamptz,
    hotels       integer NOT NULL DEFAULT 0,
    last_error   text
);
