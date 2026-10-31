package com.voyra.crm.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a small method whose body no input can reach (a catch the compiler forces, a defensive guard behind an
 * earlier check), so JaCoCo leaves it out of coverage: JaCoCo skips methods carrying an annotation whose simple name
 * contains "Generated". Keep such methods tiny and say why on the method; never put reachable logic in one.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface ExcludeFromJacocoGenerated {
}
