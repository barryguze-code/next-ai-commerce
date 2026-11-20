package com.nextaicommerce.platform.receiving;

import java.time.LocalDate;

/** The shared date-entry contract; never guesses or repairs a stored century. */
final class ExpirationDates {
    private ExpirationDates() {}
    static boolean supported(LocalDate date) {
        return date == null || (date.getYear() >= 2000 && date.getYear() <= 2099);
    }
    static void requireSupported(LocalDate date) {
        if (!supported(date)) throw new IllegalArgumentException(
            "Enter an expiration year between 2000 and 2099. Check the full year; no date was changed.");
    }
}
