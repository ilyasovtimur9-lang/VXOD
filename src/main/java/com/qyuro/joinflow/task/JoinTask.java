package com.qyuro.joinflow.task;

@FunctionalInterface
public interface JoinTask {
    /** Runs on the logical server thread. Implementations must be short and cooperative. */
    void run(JoinTaskContext context) throws Exception;
}
