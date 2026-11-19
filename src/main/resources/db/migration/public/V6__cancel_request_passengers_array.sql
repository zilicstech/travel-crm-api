-- platform_cancel_request.passengers becomes integer[] (was jsonb). Lead.kidAges is a List<Integer> stored as integer[];
-- a second List<Integer> stored as JSON made Hibernate pick one mapping for both by entity order, which in the packaged
-- jar sent leads' kid_ages as jsonb (every lead insert failed). Positions keep their order.
ALTER TABLE public.platform_cancel_request ADD COLUMN passengers_list integer[] NOT NULL DEFAULT '{}';
UPDATE public.platform_cancel_request
   SET passengers_list = ARRAY(SELECT e::int FROM jsonb_array_elements_text(passengers) WITH ORDINALITY AS t(e, n) ORDER BY n);
ALTER TABLE public.platform_cancel_request DROP COLUMN passengers;
ALTER TABLE public.platform_cancel_request RENAME COLUMN passengers_list TO passengers;
