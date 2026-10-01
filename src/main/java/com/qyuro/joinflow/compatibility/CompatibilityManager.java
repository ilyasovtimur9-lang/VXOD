package com.qyuro.joinflow.compatibility;

import com.qyuro.joinflow.QyuroJoinFlow;
import com.qyuro.joinflow.config.ServerConfig;
import net.minecraftforge.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;

/** One-time optional mod detection. Detection is observational; it does not reflect into other mods. */
public final class CompatibilityManager {
    private final Map<String, AdapterStatus> adapters = new LinkedHashMap<>();

    public void detectLoadedMods() {
        adapters.clear();
        detect("origins", "Origins", ServerConfig.originsEnabled());
        detect("apoli", "Apoli", ServerConfig.apoliEnabled());
        detect("qyurowl", "Qyurowl", ServerConfig.qyurowlEnabled());
        detect("qyurolevl", "QyuroLevl", ServerConfig.qyurolevlEnabled());
        QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Compatibility scan complete; loaded mods are observed only unless an explicit safe provider is registered.");
    }

    private void detect(String modId, String displayName, boolean enabled) {
        boolean loaded = ModList.get().isLoaded(modId);
        adapters.put(modId, new AdapterStatus(modId, displayName, loaded, enabled,
                loaded && enabled ? "OBSERVED ONLY (no automatic interception)" : "inactive"));
        if (loaded && enabled) {
            QyuroJoinFlow.LOGGER.info("[QyuroJoinFlow] Optional mod detected: {} (OBSERVED ONLY; no code interception)", displayName);
        }
    }

    public Map<String, AdapterStatus> snapshot() {
        return Map.copyOf(adapters);
    }

    public record AdapterStatus(String modId, String displayName, boolean loaded,
                                boolean enabled, String mode) { }
}
