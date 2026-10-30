package com.voyra.crm.service.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.exception.SupplierException;
import org.slf4j.Logger;
import org.springframework.web.client.RestClientResponseException;

/**
 * A book call the supplier answered with a 4xx was rejected before anything was booked (Tripjack then reports "No order
 * found"), so it is a clean failure, not an unknown outcome. 408 is left out: a request timeout may still have been processed.
 */
final class SupplierRefusal {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SupplierRefusal() {
    }

    /** The supplier's error code and message for a 4xx refusal, else null. Logs only the code and message, never the body. */
    static String of(SupplierException e, Logger log) {
        if (!(e.getCause() instanceof RestClientResponseException http) || !http.getStatusCode().is4xxClientError()
                || http.getStatusCode().value() == 408) {
            return null;
        }
        String code = "";
        String message = "";
        try {
            JsonNode err = JSON.readTree(http.getResponseBodyAsString()).path("errors").path(0);
            code = err.path("errCode").asText("");
            message = err.path("message").asText("");
        } catch (Exception ignored) {
            // an empty or non-JSON body still means the request was refused
        }
        String refusal = "Supplier refused the booking (HTTP " + http.getStatusCode().value() + ")"
                + (code.isBlank() ? "" : " " + code) + (message.isBlank() ? "" : ": " + message);
        String logged = refusal.length() > 300 ? refusal.substring(0, 300) : refusal;
        log.warn("{}", logged);
        return logged;
    }
}
