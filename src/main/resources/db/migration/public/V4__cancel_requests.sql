-- Agencies no longer cancel a platform booking at the supplier themselves: they raise a cancellation request (some
-- passengers and sectors of a flight, or a whole stay), which a platform admin reviews, cancels at the supplier by
-- hand and completes with the refund credited to the agency wallet. Owner ruling 2026-11-18.

CREATE TABLE public.platform_cancel_request (
    id                  character varying(36) PRIMARY KEY,
    platform_booking_id character varying(36) NOT NULL REFERENCES public.platform_booking (id),
    tenant_id           character varying(36) NOT NULL,
    product             character varying(10) NOT NULL,
    status              character varying(12) NOT NULL
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'COMPLETING', 'COMPLETED', 'REJECTED', 'WITHDRAWN')),
    reason              character varying(40) NOT NULL,
    final_reason        character varying(40),
    -- passenger positions in the booking's stored list (names stay encrypted there) and [{key, label}] sectors;
    -- the admin's final choice replaces them on completion
    passengers          jsonb NOT NULL DEFAULT '[]'::jsonb,
    sectors             jsonb NOT NULL DEFAULT '[]'::jsonb,
    remarks             text,
    requested_by        character varying(36) NOT NULL,
    requested_by_name   character varying(150) NOT NULL,
    started_by          character varying(36),
    started_at          timestamptz,
    supplier_refund     numeric(19, 2),
    charges             numeric(19, 2),
    voyra_fee           numeric(19, 2),
    refund              numeric(19, 2),
    recall              numeric(19, 2),
    exceeds_share       boolean NOT NULL DEFAULT false,
    offline             boolean NOT NULL DEFAULT false,
    supplier_reference  character varying(100),
    admin_note          text,
    completed_by        character varying(36),
    completed_at        timestamptz,
    finished_at         timestamptz,
    personal_data_purged_at timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- One active request per booking; another may follow once it is finished.
CREATE UNIQUE INDEX uq_cancel_request_active ON public.platform_cancel_request (platform_booking_id)
    WHERE status IN ('OPEN', 'IN_PROGRESS', 'COMPLETING');
CREATE INDEX idx_cancel_request_status ON public.platform_cancel_request (status, created_at);
CREATE INDEX idx_cancel_request_booking ON public.platform_cancel_request (platform_booking_id, created_at);

-- Proof the agency attached (medical or death certificate, airline mail); cleared with the request's personal data.
CREATE TABLE public.platform_cancel_request_file (
    id           character varying(36) PRIMARY KEY,
    request_id   character varying(36) NOT NULL REFERENCES public.platform_cancel_request (id),
    file_key     character varying(500),
    file_name    character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes   bigint NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_cancel_request_file_request ON public.platform_cancel_request_file (request_id);
