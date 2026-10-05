package com.autovpn;

import java.util.Map;

/** One server from the subscription, already converted to a sing-box outbound. */
public final class Server {
    public static final int REGULAR = 0;
    public static final int LTE = 1;
    public static final int EXCLUDED = 2;

    public String rawName;
    /** Name of the subscription this server came from. */
    public String sub = "";
    public String name;
    public String tag;
    public int group;
    public String excludeReason;
    public Map<String, Object> outbound;
    /** Real server address (outbound may point at the local Xray). */
    public String host = "";
    /**
     * Xray outbound for transports sing-box lacks (xhttp). Then {@code outbound} is a socks
     * outbound to the bundled Xray, which carries the traffic to this server.
     */
    public Map<String, Object> xray;
    /** Outbounds the Xray one dials through (sockopt.dialerProxy), with their original tags. */
    public java.util.List<Map<String, Object>> xrayDeps;

    public String groupLabel() {
        return group == LTE ? "LTE" : group == REGULAR ? "обычный" : "не участвует";
    }
}
