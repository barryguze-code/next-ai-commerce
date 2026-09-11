package com.nextaicommerce.platform.web;

/** Shared limits for interactive tables; exports traverse bounded pages separately. */
public final class TablePaging {
    public static final String DEFAULT_PARAMETER = "25";
    private TablePaging() {}
    public static int size(int requested) {
        return requested == 50 || requested == 100 ? requested : 25;
    }
}
