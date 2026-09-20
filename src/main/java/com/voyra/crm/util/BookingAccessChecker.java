package com.voyra.crm.util;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.ServiceType;

import java.util.Collection;

/**
 * Whether an agent may see and work on a booking: they logged it, or they are the assigned
 * agent on its service, or the service's type is one they manage. Reads the booking's own
 * snapshotted service_type/service_agent_id rather than joining lead_service per row - the
 * SQL translation of this exact rule lives in BookingSpecifications.accessibleToAgent, and
 * BookingScopeTest asserts the two agree on the same fixture set.
 *
 * <p>A standalone booking (service_id null, so service_type null too) structurally cannot
 * match the second or third clause - it stays visible only to the agent who logged it. A
 * service-owned booking widens for both reading and writing: the agent who covers Flight is
 * exactly who should fix a wrong PNR on someone else's Flight booking. Keep it that way -
 * narrowing writes to "own bookings only" would put the fix back with the wrong person.
 */
public final class BookingAccessChecker {

    private BookingAccessChecker() {
    }

    public static boolean agentCanAccess(Booking booking, String agentId, Collection<ServiceType> manageableTypes) {
        if (agentId.equals(booking.getAgentId())) {
            return true;
        }
        if (agentId.equals(booking.getServiceAgentId())) {
            return true;
        }
        return booking.getServiceType() != null && manageableTypes.contains(booking.getServiceType());
    }
}
