package com.qyuro.joinflow.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPriorityTest {
    @Test
    void agingMovesBackgroundWorkOneTierAtATimeAndNeverCreatesCriticalWork() {
        assertEquals(TaskPriority.LOW, TaskPriority.BACKGROUND.agedPromotion());
        assertEquals(TaskPriority.NORMAL, TaskPriority.LOW.agedPromotion());
        assertEquals(TaskPriority.HIGH, TaskPriority.NORMAL.agedPromotion());
        assertEquals(TaskPriority.HIGH, TaskPriority.HIGH.agedPromotion());
        assertEquals(TaskPriority.CRITICAL, TaskPriority.CRITICAL.agedPromotion());
    }
}
