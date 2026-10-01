package com.qyuro.joinflow.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinLearningModelTest {
    @Test
    void recordsBoundedTaskCostStatisticsAndUsesThemForLaterEstimates() {
        JoinLearningModel model = new JoinLearningModel();
        model.record("example:sync", 1_000L);
        model.record("example:sync", 2_000L);
        model.record("example:sync", 4_000L);

        JoinLearningModel.TaskSummary summary = model.summary("example:sync");
        assertEquals(3L, summary.sampleCount());
        assertEquals(7_000.0 / 3.0, summary.averageMicros(), 0.001);
        assertEquals(1_577.5, summary.emaMicros(), 0.001);
        assertEquals(2_047L, summary.medianMicrosApprox());
        assertEquals(4_095L, summary.p95MicrosApprox());
        assertEquals(4_000L, summary.maxMicros());
        assertEquals(4_000L, summary.recentMicros());

        assertEquals(1_432L, new CostEstimator().estimateMicros(model, "example:sync", 1_000L));
    }

    @Test
    void unknownTaskTypesAreFoldedIntoOneBoundedOverflowBucket() {
        JoinLearningModel model = new JoinLearningModel();
        for (int index = 0; index < 1_000; index++) model.record("addon:task_" + index, index + 1L);

        assertEquals(128, model.snapshot().size());
        assertEquals(873L, model.summary("other").sampleCount());
    }

    @Test
    void emptyTaskTypeUsesItsDeclaredEstimate() {
        JoinLearningModel model = new JoinLearningModel();
        assertEquals(750L, new CostEstimator().estimateMicros(model, "example:new_task", 750L));
        assertTrue(model.snapshot().isEmpty());
    }
}
