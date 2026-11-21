package com.hamiltel.gmlc.ops;

/**
 * Wrong username, password or TOTP code, or a disabled account. The reason is
 * deliberately not part of the response: an operator learns nothing about
 * which half of the credential was right.
 */
public class OpsAuthenticationException extends RuntimeException {
}
