package com.tk.match.output;

import org.agrona.MutableDirectBuffer;

import java.util.NavigableMap;

/**
 * MatchResult 出口：MDC publication + 同 driver 上 {@code aeron-spy:} LOCAL 录制；dedup 依赖 Archive 回放。
 */
public interface MatchResultEgress {

    /**
     * {@link io.aeron.cluster.service.ClusteredService#onStart} 调用一次：开 publication、spy 录制、dedup。
     *
     * @return 快照恢复返回 {@code consensusNextMatchSeq}；否则 {@code max(consensusNextMatchSeq, archiveLastSeq+1)}
     */
    long startMatchResultPipeline(boolean restoredFromSnapshot, long consensusNextMatchSeq);

    /** {@code matchSeq} 高于已归档上界且 publication 可用时 offer，并更新 matchSeq→position 索引。 */
    void emitEncodedMatchResult(long matchSeq, MutableDirectBuffer buffer, int offset, int length);

    void shutdown();

    NavigableMap<Long, Long> getMatchSeqIndex();

}
