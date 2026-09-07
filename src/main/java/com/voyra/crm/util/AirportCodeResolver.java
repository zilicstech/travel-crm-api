package com.voyra.crm.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Tripjack's flight search takes a 3-letter airport/city IATA code, not the free-text city
 * name the search form accepts ("Bangalore", not "BLR"). This is a fixed lookup of public,
 * factual IATA codes - not part of Tripjack's API contract - so it is safe to hardcode
 * rather than call out to a geocoding service for a value that never changes. A 3-letter
 * all-caps input is assumed to already be a code and passes through unresolved.
 */
public final class AirportCodeResolver {

    private static final Map<String, String> CITY_TO_CODE = buildMap();

    private AirportCodeResolver() {
    }

    public static String resolve(String cityOrCode) {
        if (cityOrCode == null || cityOrCode.isBlank()) {
            throw new IllegalArgumentException("Origin/destination is required");
        }
        String trimmed = cityOrCode.trim();
        if (trimmed.length() == 3 && trimmed.equals(trimmed.toUpperCase())) {
            return trimmed;
        }
        // "Bangkok, Thailand" -> "BANGKOK" - only the city name before a comma is looked up.
        String key = trimmed.split(",")[0].trim().toUpperCase();
        String code = CITY_TO_CODE.get(key);
        if (code == null) {
            throw new IllegalArgumentException(
                    "Unknown city \"" + trimmed + "\" - use its 3-letter airport code instead (e.g. BLR for Bangalore)");
        }
        return code;
    }

    private static Map<String, String> buildMap() {
        Map<String, String> map = new HashMap<>();

        // Indian domestic airports - IATA codes, public factual data.
        map.put("AGARTALA", "IXA");
        map.put("AGATTI ISLAND", "AGX");
        map.put("AGRA", "AGR");
        map.put("AHMEDABAD", "AMD");
        map.put("AIZAWL", "AJL");
        map.put("ALLAHABAD", "IXD");
        map.put("AMRITSAR", "ATQ");
        map.put("AURANGABAD", "IXU");
        map.put("BAGDOGRA", "IXB");
        map.put("BALURGHAT", "RGH");
        map.put("BANGALORE", "BLR");
        map.put("BENGALURU", "BLR");
        map.put("BAREILLY", "BEK");
        map.put("BELGAUM", "IXG");
        map.put("BHOPAL", "BHO");
        map.put("BHUBANESWAR", "BBI");
        map.put("BHUJ", "BHJ");
        map.put("BHUNTAR", "KUU");
        map.put("BIKANER", "BKB");
        map.put("BILASPUR", "PAB");
        map.put("CHANDIGARH", "IXC");
        map.put("CHENNAI", "MAA");
        map.put("COIMBATORE", "CJB");
        map.put("COOCH BEHAR", "COH");
        map.put("CUDDAPAH", "CDP");
        map.put("DAMAN", "NMB");
        map.put("DEHRADUN", "DED");
        map.put("DELHI", "DEL");
        map.put("NEW DELHI", "DEL");
        map.put("DIBRUGARH", "DIB");
        map.put("DIMAPUR", "DMU");
        map.put("DIU", "DIU");
        map.put("GAYA", "GAY");
        map.put("GOA", "GOI");
        map.put("GORAKHPUR", "GOP");
        map.put("GWALIOR", "GWL");
        map.put("HUBLI", "HBX");
        map.put("HYDERABAD", "HYD");
        map.put("IMPHAL", "IMF");
        map.put("INDORE", "IDR");
        map.put("JABALPUR", "JLR");
        map.put("JAIPUR", "JAI");
        map.put("JAISALMER", "JSA");
        map.put("JAMMU", "IXJ");
        map.put("JAMNAGAR", "JGA");
        map.put("JAMSHEDPUR", "IXW");
        map.put("JODHPUR", "JDH");
        map.put("JORHAT", "JRH");
        map.put("KAILASHAHAR", "IXH");
        map.put("KANNUR", "CNN");
        map.put("KANPUR", "KNU");
        map.put("KESHOD", "IXK");
        map.put("KHAJURAHO", "HJR");
        map.put("KOCHI", "COK");
        map.put("COCHIN", "COK");
        map.put("KOLHAPUR", "KLH");
        map.put("KOLKATA", "CCU");
        map.put("KOZHIKODE", "CCJ");
        map.put("KULLU", "KUU");
        map.put("LEH", "IXL");
        map.put("LILABARI", "IXI");
        map.put("LUCKNOW", "LKO");
        map.put("LUDHIANA", "LUH");
        map.put("MADURAI", "IXM");
        map.put("MANGALORE", "IXE");
        map.put("MUMBAI", "BOM");
        map.put("MYSORE", "MYQ");
        map.put("NAGPUR", "NAG");
        map.put("NANDED", "NDC");
        map.put("NASIK", "ISK");
        map.put("PANTNAGAR", "PGH");
        map.put("PATNA", "PAT");
        map.put("PONDICHERRY", "PNY");
        map.put("PORT BLAIR", "IXZ");
        map.put("PORBANDAR", "PBD");
        map.put("PUNE", "PNQ");
        map.put("RAIPUR", "RPR");
        map.put("RAJAHMUNDRY", "RJA");
        map.put("RAJKOT", "RAJ");
        map.put("RANCHI", "IXR");
        map.put("SHILLONG", "SHL");
        map.put("SHIMLA", "SLV");
        map.put("SHIRDI", "SAG");
        map.put("SILCHAR", "IXS");
        map.put("SRINAGAR", "SXR");
        map.put("SURAT", "STV");
        map.put("TEZPUR", "TEZ");
        map.put("THIRUVANANTHAPURAM", "TRV");
        map.put("TRIVANDRUM", "TRV");
        map.put("TIRUCHIRAPALLI", "TRZ");
        map.put("TIRUPATI", "TIR");
        map.put("TUTICORIN", "TCR");
        map.put("UDAIPUR", "UDR");
        map.put("VADODARA", "BDQ");
        map.put("VARANASI", "VNS");
        map.put("VIJAYAWADA", "VGA");
        map.put("VISAKHAPATNAM", "VTZ");

        // Common international destinations for this agency's demo/typical routes.
        map.put("BANGKOK", "BKK");
        map.put("PHUKET", "HKT");
        map.put("PARIS", "CDG");
        map.put("LONDON", "LHR");
        map.put("DUBAI", "DXB");
        map.put("SINGAPORE", "SIN");
        map.put("MALDIVES", "MLE");
        map.put("MALE", "MLE");
        map.put("TOKYO", "NRT");
        map.put("NEW YORK", "JFK");
        map.put("SWITZERLAND", "ZRH");
        map.put("ZURICH", "ZRH");
        map.put("BALI", "DPS");
        map.put("COLOMBO", "CMB");
        map.put("KUALA LUMPUR", "KUL");
        map.put("HONG KONG", "HKG");

        return map;
    }
}
