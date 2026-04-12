package com.tk.match.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Roaring64NavigableMapWrapper {
    private Roaring64NavigableMap roaring64NavigableMap;
    private Long timestamp;
}
