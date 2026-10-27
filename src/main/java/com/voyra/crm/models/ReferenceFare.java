package com.voyra.crm.models;

import java.math.BigDecimal;

/** One sample fare used to preview what a commission rule would earn. */
public record ReferenceFare(String label, BigDecimal gross, BigDecimal base) { }
