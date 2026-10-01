package com.qyuro.joinflow.learning;

public final class CostEstimator {
    public long estimateMicros(JoinLearningModel model, String taskType, long declaredEstimateMicros) {
        JoinLearningModel.TaskSummary summary = model.summary(taskType);
        if (summary.sampleCount() < 3L) return Math.max(1L, declaredEstimateMicros);
        long measured = (long) Math.max(1.0, summary.emaMicros());
        long declared = Math.max(1L, declaredEstimateMicros);
        return (measured * 3L + declared) / 4L;
    }
}
