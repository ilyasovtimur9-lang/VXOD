package com.qyuro.joinflow.api;

@FunctionalInterface
public interface ClientSyncHandler {
    /** Runs on the client thread under JoinFlow's measured client processing budget. */
    void apply(int version, byte[] immutablePayload) throws Exception;
}
