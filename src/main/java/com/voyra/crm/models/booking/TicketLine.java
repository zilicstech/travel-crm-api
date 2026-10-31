package com.voyra.crm.models.booking;

/** One passenger on one sector; {@code paxIndex} is 0-based in the supplier's traveller order, {@code sector} like "DEL-BOM". */
public record TicketLine(int paxIndex, String sector, String pnr, String ticketNumber) {
}
