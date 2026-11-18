package com.voyra.crm.models.booking;

/**
 * One seat selected for an adult or child traveller on one flight segment ({@code ssrSeatInfos: {key, code}}).
 */
public record SeatSelection(String segmentKey, String code, String seatNo) {
}
