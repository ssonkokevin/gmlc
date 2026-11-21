package com.hamiltel.gmlc.ops;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The permission a route needs. Put it on the controller class for a whole
 * surface or on a method for one endpoint. Every /v1/ops route that reads
 * tenant data must carry one; a route without it is only the session
 * endpoints themselves.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OpsRequires {
    OpsPermission value();
}
