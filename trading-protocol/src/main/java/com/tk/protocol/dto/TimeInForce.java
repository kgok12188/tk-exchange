package com.tk.protocol.dto;

/**
 * Time-in-force policy for limit orders.
 */
public enum TimeInForce {
    GTC,
    IOC,
    FOK;

    /**
     * Parse from wire values used by upstream systems.
     * Supports enum names (GTC/IOC/FOK) and legacy numeric values (1/2/3).
     *
     * @return parsed value, or null when the input is invalid
     */
    public static TimeInForce fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return switch (normalized.toUpperCase()) {
            case "GTC", "1" -> GTC;
            case "IOC", "2" -> IOC;
            case "FOK", "3" -> FOK;
            default -> null;
        };
    }
}

