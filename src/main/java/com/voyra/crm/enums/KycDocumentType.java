package com.voyra.crm.enums;

/** AGENCY_LOGO is optional, never counts as KYC, and becomes the new agency's logo on approval. */
public enum KycDocumentType {
    PAN, GST, BUSINESS_PROOF, OWNER_ID, CANCELLED_CHEQUE, ADDRESS_PROOF, IATA, OTHER, AGENCY_LOGO
}
