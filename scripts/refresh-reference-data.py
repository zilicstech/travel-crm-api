#!/usr/bin/env python3
"""Refreshes src/main/resources/reference/{airports,countries}.csv from OurAirports'
public-domain (Unlicense) open data - https://ourairports.com/data/.

Not a build step. Run by hand when the reference data needs a refresh:

    python3 scripts/refresh-reference-data.py

Filter: an airport row is kept only when it has an IATA code AND scheduled_service == 'yes'
- that is what makes it a real, bookable commercial airport rather than one of the ~82,000
private strips / heliports / closed fields OurAirports also carries. This mirrors the
"industry grounding" note in the airport-picker plan: include cities, weight by real traffic.
size_rank (0=large, 1=medium, 2=everything else) is the traffic proxy AirportCache sorts by,
since OurAirports carries no passenger-volume figure.
"""
import csv
import io
import sys
import urllib.request

AIRPORTS_URL = "https://davidmegginson.github.io/ourairports-data/airports.csv"
COUNTRIES_URL = "https://davidmegginson.github.io/ourairports-data/countries.csv"

OUT_DIR = "src/main/resources/reference"

SIZE_RANK = {"large_airport": 0, "medium_airport": 1}


def fetch_csv(url: str) -> list[dict]:
    with urllib.request.urlopen(url, timeout=60) as resp:
        text = resp.read().decode("utf-8")
    return list(csv.DictReader(io.StringIO(text)))


def main() -> None:
    print(f"Fetching {AIRPORTS_URL} ...")
    airports = fetch_csv(AIRPORTS_URL)
    print(f"  {len(airports)} raw rows")

    kept = [
        a for a in airports
        if a.get("iata_code") and a.get("scheduled_service") == "yes"
    ]
    kept.sort(key=lambda a: (SIZE_RANK.get(a["type"], 2), a["iata_code"]))
    print(f"  {len(kept)} rows kept (iata_code set AND scheduled_service=yes)")

    print(f"Fetching {COUNTRIES_URL} ...")
    countries = fetch_csv(COUNTRIES_URL)
    countries.sort(key=lambda c: c["name"])
    print(f"  {len(countries)} country rows")

    known_countries = {c["code"] for c in countries}
    missing = {a["iso_country"] for a in kept} - known_countries
    if missing:
        print(f"  WARNING: {len(missing)} airport country codes have no countries.csv match: {sorted(missing)}")

    airports_out = f"{OUT_DIR}/airports.csv"
    with open(airports_out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["iata", "name", "city", "country_code", "size_rank"])
        for a in kept:
            w.writerow([
                a["iata_code"],
                a["name"],
                a["municipality"] or a["name"],
                a["iso_country"],
                SIZE_RANK.get(a["type"], 2),
            ])
    print(f"Wrote {airports_out} ({len(kept)} rows)")

    countries_out = f"{OUT_DIR}/countries.csv"
    with open(countries_out, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["code", "name"])
        for c in countries:
            w.writerow([c["code"], c["name"]])
    print(f"Wrote {countries_out} ({len(countries)} rows)")

    if not (3500 <= len(kept) <= 5000):
        print(f"WARNING: airport row count {len(kept)} is outside the expected 3500-5000 band - "
              f"check the source data before committing.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
