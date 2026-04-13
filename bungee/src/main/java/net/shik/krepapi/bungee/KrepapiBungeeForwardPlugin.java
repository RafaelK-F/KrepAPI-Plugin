package net.shik.krepapi.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

/**
 * When players connect through BungeeCord or Waterfall, custom payload (plugin message) channels must be known to the
 * proxy. This plugin registers all KrepAPI play channels so the Paper backend can receive {@code krepapi:c2s_client_info}
 * and complete the handshake (same role as {@code KrepapiVelocityForwardPlugin} on Velocity).
 */
public final class KrepapiBungeeForwardPlugin extends Plugin {

    private static final String NS = "krepapi";

    /** Same ids as {@code net.shik.krepapi.protocol.KrepapiChannels} (protocol JAR not bundled here). */
    private static final String[] CHANNELS = {
            NS + ":s2c_hello",
            NS + ":s2c_bindings",
            NS + ":s2c_raw_capture",
            NS + ":s2c_intercept_keys",
            NS + ":s2c_mouse_capture",
            NS + ":c2s_client_info",
            NS + ":c2s_key_action",
            NS + ":c2s_raw_key",
            NS + ":c2s_mouse_action",
    };

    @Override
    public void onEnable() {
        ProxyServer proxy = getProxy();
        for (String ch : CHANNELS) {
            proxy.registerChannel(ch);
        }
        getLogger().info("KrepAPI Forward: registered " + CHANNELS.length + " channel(s) for proxy forwarding.");
    }
}
