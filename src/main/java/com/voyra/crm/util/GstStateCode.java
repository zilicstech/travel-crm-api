package com.voyra.crm.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * India's statutory GST state/UT codes (the first two digits of a GSTIN). A fixed reference
 * list, not a database table - it does not vary per tenant and is not admin-editable.
 */
public final class GstStateCode {

    private static final Map<String, String> CODES = new LinkedHashMap<>();

    static {
        CODES.put("01", "Jammu and Kashmir");
        CODES.put("02", "Himachal Pradesh");
        CODES.put("03", "Punjab");
        CODES.put("04", "Chandigarh");
        CODES.put("05", "Uttarakhand");
        CODES.put("06", "Haryana");
        CODES.put("07", "Delhi");
        CODES.put("08", "Rajasthan");
        CODES.put("09", "Uttar Pradesh");
        CODES.put("10", "Bihar");
        CODES.put("11", "Sikkim");
        CODES.put("12", "Arunachal Pradesh");
        CODES.put("13", "Nagaland");
        CODES.put("14", "Manipur");
        CODES.put("15", "Mizoram");
        CODES.put("16", "Tripura");
        CODES.put("17", "Meghalaya");
        CODES.put("18", "Assam");
        CODES.put("19", "West Bengal");
        CODES.put("20", "Jharkhand");
        CODES.put("21", "Odisha");
        CODES.put("22", "Chhattisgarh");
        CODES.put("23", "Madhya Pradesh");
        CODES.put("24", "Gujarat");
        CODES.put("26", "Dadra and Nagar Haveli and Daman and Diu");
        CODES.put("27", "Maharashtra");
        CODES.put("29", "Karnataka");
        CODES.put("30", "Goa");
        CODES.put("31", "Lakshadweep");
        CODES.put("32", "Kerala");
        CODES.put("33", "Tamil Nadu");
        CODES.put("34", "Puducherry");
        CODES.put("35", "Andaman and Nicobar Islands");
        CODES.put("36", "Telangana");
        CODES.put("37", "Andhra Pradesh");
        CODES.put("38", "Ladakh");
    }

    private GstStateCode() {
    }

    public static Map<String, String> all() {
        return CODES;
    }

    public static boolean isValid(String code) {
        return code != null && CODES.containsKey(code);
    }

    public static String nameOf(String code) {
        return CODES.get(code);
    }
}
