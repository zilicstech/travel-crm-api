-- Travel insurance as its own service and booking type (client 27 Nov). Expand only: every column is nullable.
-- A lead's Insurance service: the cover type, the country the traveller leaves from, the countries travelled, the dates and persons.
ALTER TABLE lead_service ADD COLUMN insurance_type varchar(20);
ALTER TABLE lead_service ADD COLUMN insurance_from_country varchar(80);
ALTER TABLE lead_service ADD COLUMN insurance_countries varchar(500);
ALTER TABLE lead_service ADD COLUMN insurance_start date;
ALTER TABLE lead_service ADD COLUMN insurance_end date;
ALTER TABLE lead_service ADD COLUMN insurance_persons integer;

-- An Insurance booking keeps its dates in journey_date / return_date and its countries in destination.
ALTER TABLE booking ADD COLUMN insurance_type varchar(20);
ALTER TABLE booking ADD COLUMN insurance_from_country varchar(80);
ALTER TABLE booking ADD COLUMN insurance_persons integer;
