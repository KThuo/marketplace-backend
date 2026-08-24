package com.hodi.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Override the auto-derived action label used by {@link RequestLoggingAspect}. Place on a
 * controller method when the conventional {@code METHOD CONTROLLER} label is unhelpful
 * (e.g., {@code "ROTATE TENANT KEY"} reads better than {@code "ROTATE KEY TENANT APP"}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestAction {
    String value();
}
