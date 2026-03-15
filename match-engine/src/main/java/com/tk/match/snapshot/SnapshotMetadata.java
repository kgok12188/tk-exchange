package com.tk.match.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * First line of snapshot file: order book description.
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
}
