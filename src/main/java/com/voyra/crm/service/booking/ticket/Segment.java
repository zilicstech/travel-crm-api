package com.voyra.crm.service.booking.ticket;

/** One flight segment as printed; any field the supplier did not give is null (the PDF prints "-"). */
public record Segment(String id, String airlineCode, String airlineName, String flightNumber,
                      String fromCode, String fromCity, String fromTerminal, String fromAirport,
                      String toCode, String toCity, String toTerminal, String toAirport,
                      String departs, String arrives, Integer durationMinutes) {

    public Segment(String airlineCode, String airlineName, String flightNumber,
                   String fromCode, String fromCity, String fromTerminal, String fromAirport,
                   String toCode, String toCity, String toTerminal, String toAirport,
                   String departs, String arrives, Integer durationMinutes) {
        this(null, airlineCode, airlineName, flightNumber, fromCode, fromCity, fromTerminal, fromAirport,
                toCode, toCity, toTerminal, toAirport, departs, arrives, durationMinutes);
    }
}
