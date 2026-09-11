package com.nextaicommerce.platform.receiving;

/** Normal coordination signal: another tenant inventory operation currently owns the shared lock. */
final class PhysicalCountBusyException extends RuntimeException {
    PhysicalCountBusyException(){super("Waiting for the current order sync to finish");}
}
