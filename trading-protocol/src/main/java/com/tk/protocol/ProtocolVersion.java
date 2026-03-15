package com.tk.protocol;

/**
 * Protocol schema version for evolution. Include in message headers or DTOs (e.g. OrderCommand.schemaVersion, MatchResponse.schemaVersion).
 */
public final class ProtocolVersion {
    public static final int CURRENT = 1;

    private ProtocolVersion() {}
}
