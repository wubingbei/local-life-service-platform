package com.llsp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxRelayServiceTest {

    private final OutboxRelayService service = new OutboxRelayService();

    @Test
    void calculateBackoffDelay_growsExponentially() {
        assertEquals(10_000L, service.calculateBackoffDelay(0));
        assertEquals(20_000L, service.calculateBackoffDelay(1));
        assertEquals(40_000L, service.calculateBackoffDelay(2));
        assertEquals(80_000L, service.calculateBackoffDelay(3));
    }

    @Test
    void calculateBackoffDelay_cappedAtMax() {
        assertEquals(600_000L, service.calculateBackoffDelay(10));
        assertEquals(600_000L, service.calculateBackoffDelay(20));
    }
}
