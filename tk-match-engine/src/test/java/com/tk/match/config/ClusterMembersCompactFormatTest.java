package com.tk.match.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterMembersCompactFormatTest {

    @Test
    void singleMember_expandsToAeronCanonical() {
        assertEquals(
                "0,192.168.1.2:20110,192.168.1.2:20220,192.168.1.2:20330,192.168.1.2:20440,192.168.1.2:8010",
                ClusterMembersCompactFormat.toAeronCanonical(
                        "0=192.168.1.2:20110:20220:20330:20440:8010"));
    }

    @Test
    void twoMembers_pipeSeparated() {
        assertEquals(
                "0,192.168.1.2:20110,192.168.1.2:20220,192.168.1.2:20330,192.168.1.2:20440,192.168.1.2:8010"
                        + "|"
                        + "1,192.168.1.3:20110,192.168.1.3:20220,192.168.1.3:20330,192.168.1.3:20440,192.168.1.3:8010",
                ClusterMembersCompactFormat.toAeronCanonical(
                        "0=192.168.1.2:20110:20220:20330:20440:8010|1=192.168.1.3:20110:20220:20330:20440:8010"));
    }

    @Test
    void duplicateMemberIds_sameHostAsUserExample_stillParses() {
        assertEquals(
                "0,192.168.1.2:20110,192.168.1.2:20220,192.168.1.2:20330,192.168.1.2:20440,192.168.1.2:8010"
                        + "|"
                        + "1,192.168.1.2:20110,192.168.1.2:20220,192.168.1.2:20330,192.168.1.2:20440,192.168.1.2:8010",
                ClusterMembersCompactFormat.toAeronCanonical(
                        "0=192.168.1.2:20110:20220:20330:20440:8010|1=192.168.1.2:20110:20220:20330:20440:8010"));
    }

    @Test
    void missingEquals_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                ClusterMembersCompactFormat.toAeronCanonical("0,localhost:20110"));
    }

    @Test
    void wrongSegmentCount_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                ClusterMembersCompactFormat.toAeronCanonical("0=localhost:20110:20220"));
    }
}
