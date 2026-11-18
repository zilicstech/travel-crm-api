package com.voyra.crm.models;

/**
 * One hotel of a supplier's catalogue, as stored for name search. Text is trimmed and cut to fit a sensible length
 * here, so one odd supplier value never fails a whole batch; stars outside 1-5 become null.
 */
public record CatalogHotel(String hotelId, String name, String city, String countryCode, String countryName,
                           Integer starRating, String propertyType) {

    public CatalogHotel {
        hotelId = cut(hotelId, 40);
        name = cut(name, 200);
        city = cut(city, 100);
        countryCode = cut(countryCode, 2);
        countryName = cut(countryName, 100);
        starRating = starRating != null && starRating >= 1 && starRating <= 5 ? starRating : null;
        propertyType = cut(propertyType, 60);
    }

    private static String cut(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
