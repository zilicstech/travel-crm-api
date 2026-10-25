package com.voyra.crm.models.booking;

import java.time.LocalDate;

/** One traveller. {@code type} is ADULT, CHILD or INFANT; passport fields are optional (domestic). */
public record PaxDetail(String type, String title, String firstName, String lastName, LocalDate dateOfBirth,
                        String passportNumber, LocalDate passportExpiry, String nationality) {
}
