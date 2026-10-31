package com.voyra.crm.service.booking.ticket;

import java.util.List;

/** One direction of the trip ("DEL-BOM") with the fare facts of the fare it was sold on; unknown facts are null. */
public record Direction(String key, List<Segment> segments, String fareType, String cabinClass, Boolean refundable,
                        String checkInBaggage, String cabinBaggage) {
}
