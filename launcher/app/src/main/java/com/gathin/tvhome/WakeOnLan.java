package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Wake-on-LAN for the Wake PC pill (config.xml wake_pc_mac): one UDP magic packet, sent on the local network only. */
final class WakeOnLan {
    /** The usual Wake-on-LAN port ("discard"). */
    private static final int PORT = 9;

    private WakeOnLan() {}

    /** "AA-BB-CC-DD-EE-FF" or "aa:bb:cc:dd:ee:ff" to 12 upper-case hex digits; anything else is empty (pill off). */
    static String normalize(String mac) {
        String hex = mac == null ? "" : mac.replaceAll("[\s:.-]", "");
        return hex.matches("[0-9A-Fa-f]{12}") ? hex.toUpperCase(Locale.ROOT) : "";
    }

    /** Six 0xFF bytes, then the MAC 16 times. */
    static byte[] packet(String mac) {
        byte[] addr = new byte[6];
        for (int i = 0; i < 6; i++) addr[i] = (byte) Integer.parseInt(mac.substring(i * 2, i * 2 + 2), 16);
        byte[] out = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) out[i] = (byte) 0xFF;
        for (int i = 6; i < out.length; i++) out[i] = addr[(i - 6) % 6];
        return out;
    }

    /** "AABBCCDDEEFF" as "AA-BB-CC-DD-EE-FF", for the settings row. */
    static String pretty(String hex) {
        if (hex.length() != 12) return hex;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) out.append(i == 0 ? "" : "-").append(hex, i, i + 2);
        return out.toString();
    }

    /** The name the TV gives that input (its custom name first), or null when it is gone. */
    static CharSequence inputLabel(Context context, String inputId) {
        try {
            android.media.tv.TvInputManager tim =
                    (android.media.tv.TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            android.media.tv.TvInputInfo info = tim == null ? null : tim.getTvInputInfo(inputId);
            if (info == null) return null;
            CharSequence custom = info.loadCustomLabel(context);
            return custom != null && custom.length() > 0 ? custom : info.loadLabel(context);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Sends the packet three times to the local subnet's broadcast address (192.168.x.255 on a /24), port 9.
     * Wake-on-LAN has to broadcast: a sleeping PC has no IP address to send to, so its network card listens for
     * its own MAC in every packet, and every other device drops the 102 bytes. The packet never leaves the
     * network: routers do not forward broadcasts. 255.255.255.255 only when no subnet is known. Blocking: call
     * off the main thread.
     */
    static boolean send(Context context, String mac) {
        if (normalize(mac).isEmpty()) return false;
        byte[] data = packet(normalize(mac));
        Set<InetAddress> targets = subnetBroadcasts(context);
        if (targets.isEmpty()) {
            try {
                targets.add(InetAddress.getByAddress(new byte[] {-1, -1, -1, -1}));
            } catch (Exception ignored) {
            }
        }
        boolean sent = false;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (InetAddress to : targets) {
                    try {
                        socket.send(new DatagramPacket(data, data.length, to, PORT));
                        sent = true;
                    } catch (Exception ignored) {
                        // That address is not reachable from here; the others may be.
                    }
                }
            }
        } catch (Exception e) {
            return sent;
        }
        return sent;
    }

    private static Set<InetAddress> subnetBroadcasts(Context context) {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return out;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (!(la.getAddress() instanceof Inet4Address)) continue;
                    int prefix = la.getPrefixLength();
                    if (prefix <= 0 || prefix >= 31) continue;
                    byte[] ip = la.getAddress().getAddress();
                    int value = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16) | ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF);
                    int broadcast = value | (-1 >>> prefix);
                    out.add(InetAddress.getByAddress(new byte[] {
                            (byte) (broadcast >>> 24), (byte) (broadcast >>> 16), (byte) (broadcast >>> 8), (byte) broadcast}));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
