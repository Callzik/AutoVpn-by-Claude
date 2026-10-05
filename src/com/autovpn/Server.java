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

    public String groupLabel() {
        return group == LTE ? "LTE" : group == REGULAR ? "обычный" : "не участвует";
    }
}
