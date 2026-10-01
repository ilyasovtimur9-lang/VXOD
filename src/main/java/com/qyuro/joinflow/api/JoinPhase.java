package com.qyuro.joinflow.api;

/**
 * Named points in a connection lifecycle. Forge exposes safe lifecycle events for only some
 * points; JoinFlow only schedules explicitly deferrable POST_JOIN work by default.
 */
public enum JoinPhase {
    CONNECT,
    NEGOTIATION,
    AUTH,
    ESSENTIAL_DATA,
    CAPABILITIES,
    ORIGINS_COMPATIBILITY,
    MOD_COMPATIBILITY,
    PLAYER_SYNC,
    CHUNK_PHASE,
    NETWORK_SYNC,
    FINALIZE,
    POST_JOIN,
    COMPLETE
}
