package com.tk.match.config;

/**
 * {@code match.cluster.cluster-members} 紧凑格式 → {@link io.aeron.cluster.ClusterMember#parse(String)} 规范串。
 * 每条 {@code id=host:p1:p2:p3:p4:p5}（ingress…archive 五端口），多成员 {@code |} 分隔。
 */
public final class ClusterMembersCompactFormat {

    private ClusterMembersCompactFormat() {
    }

    /**
     * @param compact 多条 {@code id=host:p1:p2:p3:p4:p5}，以 {@code |} 分隔
     * @return {@code id,host:p1,host:p2,host:p3,host:p4,host:p5|...}，供 ConsensusModule 使用
     */
    public static String toAeronCanonical(final String compact) {
        if (compact == null || compact.isBlank()) {
            throw new IllegalArgumentException("clusterMembers must not be blank");
        }
        final String[] memberTokens = compact.trim().split("\\|");
        final StringBuilder result = new StringBuilder();
        for (int memberIndex = 0; memberIndex < memberTokens.length; memberIndex++) {
            if (memberIndex > 0) {
                result.append('|');
            }
            result.append(convertOneMember(memberTokens[memberIndex].trim()));
        }
        return result.toString();
    }

    private static String convertOneMember(final String memberSpec) {
        final int equalsIndex = memberSpec.indexOf('=');
        if (equalsIndex <= 0) {
            throw new IllegalArgumentException(
                    "clusterMembers entry must be id=host:ingress:consensus:log:catchup:archive, got: " + memberSpec);
        }
        final String idPart = memberSpec.substring(0, equalsIndex).trim();
        final String hostAndPorts = memberSpec.substring(equalsIndex + 1).trim();
        try {
            Integer.parseInt(idPart);
        } catch (NumberFormatException numberFormatException) {
            throw new IllegalArgumentException("invalid cluster member id: " + idPart, numberFormatException);
        }
        final String[] parts = hostAndPorts.split(":", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "clusterMembers entry must be host and exactly 5 ports (6 colon-separated segments), got "
                            + parts.length + " segments in: " + memberSpec);
        }
        final String host = parts[0].trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host is empty in: " + memberSpec);
        }
        for (int portIndex = 1; portIndex <= 5; portIndex++) {
            parsePort(parts[portIndex].trim(), memberSpec);
        }
        return idPart + ","
                + host + ":" + parts[1].trim() + ","
                + host + ":" + parts[2].trim() + ","
                + host + ":" + parts[3].trim() + ","
                + host + ":" + parts[4].trim() + ","
                + host + ":" + parts[5].trim();
    }

    private static void parsePort(final String portToken, final String memberSpec) {
        try {
            final int port = Integer.parseInt(portToken);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("port out of range: " + portToken + " in " + memberSpec);
            }
        } catch (NumberFormatException numberFormatException) {
            throw new IllegalArgumentException("invalid port: " + portToken + " in " + memberSpec, numberFormatException);
        }
    }
}
