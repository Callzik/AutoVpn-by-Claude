package app.dash;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Addresses hotspot / USB / Bluetooth tethering clients can reach the phone at. */
public final class ShareInfo {
    private ShareInfo() {}

    /** Interface name prefixes Android uses for tethering (Wi-Fi hotspot, USB, Bluetooth, Ethernet). */
    private static final String[] TETHER = {"ap", "swlan", "softap", "wlan", "rndis", "usb", "ncm", "bt-pan", "eth"};

    /** Private IPv4 addresses of tethering interfaces, hotspot first; empty when tethering is off. */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase(java.util.Locale.ROOT);
                if (!isTether(name)) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    // the phone itself as a Wi-Fi client is .x of someone else's network, a hotspot is usually .1
                    if (ip.endsWith(".1")) out.add(0, ip); else out.add(ip);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static String addresses() {
        List<String> l = list();
        return l.isEmpty() ? "точка доступа не включена" : android.text.TextUtils.join(", ", l);
    }

    private static boolean isTether(String name) {
        for (String p : TETHER) if (name.startsWith(p)) return true;
        return false;
    }
}
