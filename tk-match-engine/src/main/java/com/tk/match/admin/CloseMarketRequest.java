package com.tk.match.admin;

public class CloseMarketRequest {

    private int symbolId;
    private long configVersion;
    private boolean force;

    public int getSymbolId() { return symbolId; }
    public void setSymbolId(int symbolId) { this.symbolId = symbolId; }

    public long getConfigVersion() { return configVersion; }
    public void setConfigVersion(long configVersion) { this.configVersion = configVersion; }

    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
