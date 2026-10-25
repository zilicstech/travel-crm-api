package com.voyra.crm.models;

/**
 * Voyra's own identity on its subscription tax invoices, from platform settings. Blank settings are null;
 * the state code falls back to the GSTIN's first two digits.
 */
public record VoyraLegal(String legalName, String gstin, String address, String stateCode) {
}
