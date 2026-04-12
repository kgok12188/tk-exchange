package com.tk.match.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tk.protocol.dto.MarketConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;

/**
 * First line of snapshot file: order book description。
 * 可选 {@link #marketConfig} 与 {@link #marketConfigVersion} 与挂单同属该 offset 一致点（设计 §10）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SnapshotMetadata {
    private long offset;
    private int orderCount;
    private String symbol;
    private Long ts;
    /**
     * 可选；与 {@link #offset} 一致点下的 {@link MarketConfig}。
     */
    private MarketConfig marketConfig;
    /**
     * 可选；已应用的配置版本（与 {@link com.tk.protocol.dto.MarketUpdatePayload#getConfigVersion()} 对齐）；缺省表示未记录或老快照。
     */
    private Long marketConfigVersion;
    /**
     * 元数据格式版本，便于字段演进。
     */
    private Integer metadataVersion;

    private LinkedHashMap<String, String> navigable;
}
