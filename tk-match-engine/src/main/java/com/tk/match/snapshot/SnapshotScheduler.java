package com.tk.match.snapshot;

import com.tk.match.config.MatchEngineConfig;
import com.tk.match.ha.MatchLeaderElectionService;
import com.tk.match.service.MatchManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled task: for each slot, get symbols and submit a snapshot request per symbol.
 * 仅从节点打快照；主节点仅在 ZK 发现参与数 ≤1（仅自己）时打快照，否则由从节点负责。
 * 仅当 match.snapshotEnabled=true 且 match.snapshotDir 已设置时执行。
 */
@Service
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final MatchManager matchManager;
    private final MatchLeaderElectionService matchLeaderElectionService;

    @Value("${match.snapshot-interval-ms:300000}")
    private long matchSnapshotIntervalMs = 300000;

    private final MatchEngineConfig matchEngineConfig;

    public SnapshotScheduler(MatchManager matchManager,
                             MatchLeaderElectionService matchLeaderElectionService,
                             com.tk.match.config.MatchEngineConfig matchConfig) {
        this.matchManager = matchManager;
        this.matchLeaderElectionService = matchLeaderElectionService;
        this.matchEngineConfig = matchConfig;
    }

    @Scheduled(fixedDelayString = "${match.snapshot-interval-ms:300000}", initialDelay = 1000 * 60)
    public void triggerSnapshots() {
        if (matchEngineConfig.isSnapshotEnabled()) {
            if (matchManager.anyMaster()) {
                int participants = matchLeaderElectionService.getParticipantCount();
                if (participants > 1) {
                    if (log.isTraceEnabled()) {
                        log.trace("Snapshot skipped: master with {} participants (slaves will snapshot)", participants);
                    }
                    return;
                }
            }
            int slotCount = matchManager.getSlotCount();
            if (slotCount == 0) return;
            int total = 0;
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                total += matchManager.getSymbolsBySlotIndex(slotIndex).size();
            }
            long interval = Math.min(matchSnapshotIntervalMs / total, 2000);
            for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
                for (String symbol : matchManager.getSymbolsBySlotIndex(slotIndex)) {
                    matchManager.submitSnapshotRequest(symbol);
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException interruptedException) {
                        // ignore
                    }
                }
            }
            if (total > 0 && log.isDebugEnabled()) {
                log.debug("Snapshot schedule submitted {} snapshot request(s) across {} slot(s)", total, slotCount);
            }
        }
    }

}
