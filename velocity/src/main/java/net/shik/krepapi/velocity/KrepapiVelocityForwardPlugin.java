package net.shik.krepapi.velocity;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/**
 * When players connect through Velocity, custom payload (plugin message) channels must be known to the proxy.
 * This plugin registers all KrepAPI play channels and explicitly forwards traffic so the Paper backend can receive
 * {@code krepapi:c2s_client_info} and complete the handshake.
 */
@Plugin(
        id = "krepapiforward",
        name = "KrepAPI Forward",
        version = "1.1.0",
        authors = {"shik"}
)
public final class KrepapiVelocityForwardPlugin {

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

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public KrepapiVelocityForwardPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        server.getEventManager().register(this, this);
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        for (String ch : CHANNELS) {
            server.getChannelRegistrar().register(MinecraftChannelIdentifier.from(ch));
        }
        logger.info("KrepAPI Forward: registered {} channel(s) for proxy forwarding.", CHANNELS.length);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        ChannelIdentifier id = event.getIdentifier();
        if (id == null) {
            return;
        }
        String s = id.getId();
        if (s == null || !s.startsWith(NS + ":")) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.forward());
    }
}
