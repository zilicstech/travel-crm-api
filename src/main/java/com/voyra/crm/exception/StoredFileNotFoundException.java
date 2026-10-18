package com.voyra.crm.exception;

/**
 * A stored file's record exists but its bytes are gone from storage. Its own type so the client
 * gets a clean 404 "File not found" - the storage key names the tenant schema and folder layout,
 * so it is logged server-side and never echoed back.
 */
public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException() {
        super("File not found");
    }
}
