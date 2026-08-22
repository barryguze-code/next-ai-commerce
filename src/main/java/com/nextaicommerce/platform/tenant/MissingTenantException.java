package com.nextaicommerce.platform.tenant;

public class MissingTenantException extends IllegalStateException {
    public MissingTenantException() { super("An authenticated tenant context is required"); }
}
