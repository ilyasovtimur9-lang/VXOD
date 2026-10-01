package com.qyuro.joinflow.network;

/** Hash/version check used by the optional static-sync path; mutable server state is not delta-synced. */
public final class DeltaSync {
    private DeltaSync() {
    }

    public static boolean matches(int clientVersion, String clientHash, SyncDataRegistry.StaticData serverData) {
        return serverData != null && clientVersion == serverData.version()
                && serverData.hash().equals(clientHash);
    }
}
