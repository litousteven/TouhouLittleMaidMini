package com.touhoulittlemaid.mini.neoforge.mcp;

import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.mcp.CompanionTools;

import java.util.List;
import java.util.UUID;

/**
 * NeoForge companion backend.
 *
 * <p>NeoForge adaptation is scheduled for M5, after the Fabric M0-M4 path is stable.
 * Until the NeoForge companion entity exists, this backend reports no companions and
 * refuses every action explicitly rather than pretending to succeed.
 */
public final class NeoForgeCompanionBackend implements CompanionTools.CompanionBackend {

    private static final String UNAVAILABLE =
            "NeoForge companion support is scheduled for M5";

    @Override
    public List<CompanionSnapshot> listCompanions() {
        return List.of();
    }

    @Override
    public CompanionSnapshot state(UUID companionId) {
        return null;
    }

    @Override
    public ToolResult move(UUID companionId, int x, int y, int z, double speed) {
        return unavailable();
    }

    @Override
    public ToolResult interact(UUID companionId, int x, int y, int z) {
        return unavailable();
    }

    @Override
    public ToolResult inventory(UUID companionId, int slot) {
        return unavailable();
    }

    @Override
    public ToolResult setBehavior(UUID companionId, LocalBehavior behavior) {
        return unavailable();
    }

    @Override
    public ToolResult stop(UUID companionId) {
        return unavailable();
    }

    @Override
    public ToolResult wait(UUID companionId, int ticks) {
        return unavailable();
    }

    private static ToolResult unavailable() {
        return ToolResult.rejected("unsupported_loader", UNAVAILABLE);
    }
}
