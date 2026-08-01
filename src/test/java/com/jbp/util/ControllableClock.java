package com.jbp.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Lets a test move time forward deliberately, so window behaviour is verified without the suite
 * pausing for a real minute.
 *
 * <p>Public and standalone rather than nested in one test, because both {@link CallRateLimiter} and
 * the transport decorators that wrap it need to control time, and they sit in different packages.
 */
public final class ControllableClock extends Clock {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public void advanceMillis(long millis) {
        now = now.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
