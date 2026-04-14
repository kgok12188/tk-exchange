package com.tk.match.admin;

public final class AdminCommandResult {

    private final boolean success;
    private final String message;

    public AdminCommandResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
