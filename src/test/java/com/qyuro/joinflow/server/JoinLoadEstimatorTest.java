package com.qyuro.joinflow.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinLoadEstimatorTest {
    @Test
    void projectionIsCappedAndAddsOnlyItsConservativeSchedulerSignal() {
        JoinLoadEstimator estimator = new JoinLoadEstimator();

        assertEquals(10.102, estimator.predictMspt(10.0, 2, 100_000L, 2_000.0, 1), 0.001);
        assertEquals(204.0, estimator.predictMspt(200.0, 100, Long.MAX_VALUE, 1_000_000.0, 64), 0.001);
        assertEquals(12.5, estimator.predictMspt(12.5, 0, 0L, 1_000.0, 1), 0.001);
    }
}
