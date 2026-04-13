package net.shik.krepapi.plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;
import net.shik.krepapi.protocol.KrepapiCapabilities;
import net.shik.krepapi.protocol.KrepapiChannels;
import net.shik.krepapi.protocol.KrepapiKickReasons;
import net.shik.krepapi.protocol.KrepapiProtocolVersion;
import net.shik.krepapi.protocol.KrepapiServerDebug;
import net.shik.krepapi.protocol.KrepapiVersionPolicy;
import net.shik.krepapi.protocol.KrepapiVersionRequirement;
import net.shik.krepapi.protocol.ProtocolBuf;
import net.shik.krepapi.protocol.ProtocolMessages;

/**
 * Paper-side KrepAPI integration: plugin-message handshake, config-driven key bindings, and dispatch of server
 * commands when the client reports a bound key press. Integrators can copy the handshake and channel wiring; operators
 * only edit {@code config.yml}.
 */
public final class KrepAPIPlugin extends JavaPlugin implements Listener, PluginMessageListener {

    /** Wall-clock cooldown between binding commands per player (~5 server ticks at 20 TPS); avoids {@code Server#getCurrentTick()} for wider Paper API compatibility. */
    private static final long BINDING_COOLDOWN_NANOS = 250_000_000L;

    private final Map<UUID, PendingHandshake> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> clientCapabilities = new ConcurrentHashMap<>();
    private final Map<Plugin, CopyOnWriteArrayList<KrepapiVersionPolicy.Constraint>> constraintsByPlugin =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBindingCommandNanos = new ConcurrentHashMap<>();

    private List<ProtocolMessages.BindingEntry> bindingEntries = List.of();
    private Map<String, String> actionToCommand = Map.of();

    private @Nullable KrepAPIPluginUpdateService pluginUpdateService;

    /** NDJSON stream; same layout as KrepAPI Paper reference plugin. */
    private java.io.Writer debugWriter;
    private final Object debugLock = new Object();

    private boolean isDebug() {
        return getConfig().getBoolean("debug-logging", false)
                || getConfig().getBoolean("debug", false)
                || KrepapiServerDebug.jvmOrMarkerFileEnabled();
    }

    boolean isKrepapiDebugActive() {
        return isDebug();
    }

    private void debug(String msg) {
        if (!isDebug()) {
            return;
        }
        getLogger().info("[KrepAPI-Debug] " + msg);
    }

    private void debugJson(String event, String fieldsJson) {
        synchronized (debugLock) {
            if (debugWriter == null) {
                return;
            }
            try {
                debugWriter.write("{\"ts\":" + System.currentTimeMillis()
                        + ",\"event\":\"" + event + "\"," + fieldsJson + "}\n");
                debugWriter.flush();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    private void openDebugFile() {
        if (!isDebug()) {
            return;
        }
        synchronized (debugLock) {
            try {
                java.io.File dir = getDataFolder();
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                java.io.File f = new java.io.File(dir, "debug-" + System.currentTimeMillis() + ".json");
                debugWriter = new java.io.FileWriter(f, true);
                getLogger().info("[KrepAPI-Debug] NDJSON debug log: " + f.getName());
            } catch (java.io.IOException ex) {
                getLogger().warning("[KrepAPI-Debug] Failed to open debug log: " + ex.getMessage());
            }
        }
    }

    private void closeDebugFile() {
        synchronized (debugLock) {
            if (debugWriter != null) {
                try {
                    debugWriter.close();
                } catch (java.io.IOException ignored) {
                }
                debugWriter = null;
            }
        }
    }

    /** After {@link #reloadConfig()}, re-open or close the NDJSON file so {@code debug-logging} changes apply. */
    void refreshDebugLoggingAfterConfigReload() {
        closeDebugFile();
        openDebugFile();
    }

    /** Persists {@code debug-logging} and removes legacy {@code debug} so file and runtime match. */
    void saveDebugLoggingToConfig(boolean enabled) {
        getConfig().set("debug-logging", enabled);
        getConfig().set("debug", null);
        saveConfig();
        refreshDebugLoggingAfterConfigReload();
    }

    void saveRequireKrepapiToConfig(boolean require) {
        getConfig().set("require-krepapi", require);
        saveConfig();
    }

    void saveMinimumModVersionToConfig(@NotNull String version) {
        String trimmed = version.trim();
        KrepapiVersionRequirement.parse(trimmed);
        getConfig().set("minimum-mod-version", trimmed);
        saveConfig();
    }

    void saveHandshakeTimeoutTicks(long ticks) {
        if (ticks < 1L) {
            throw new IllegalArgumentException("handshake-timeout-ticks must be at least 1");
        }
        getConfig().set("handshake-timeout-ticks", ticks);
        saveConfig();
    }

    /** Cached remote plugin version check (same {@code versions.json} as the KrepAPI repo). */
    @Nullable KrepAPIPluginUpdateService.PluginUpdateSnapshot getPluginUpdateSnapshot() {
        return pluginUpdateService == null ? null : pluginUpdateService.getSnapshot();
    }

    void refreshPluginUpdateAfterReload() {
        if (pluginUpdateService != null) {
            pluginUpdateService.refreshAfterConfigReload();
        }
    }

    /** Console-only hex dump of plugin-message payloads (not written to NDJSON). */
    private void debugPayload(String prefix, byte[] payload) {
        if (!isDebug()) {
            return;
        }
        String hex = HexFormat.of().withUpperCase().formatHex(payload);
        getLogger().info("[KrepAPI-Debug] " + prefix + " (" + payload.length + " bytes): " + hex);
    }

    // ── State accessors (for /krepapi command) ──────────────────────────

    Map<UUID, PendingHandshake> pendingHandshakes() {
        return pending;
    }

    Map<UUID, Integer> capabilities() {
        return clientCapabilities;
    }

    List<ProtocolMessages.BindingEntry> bindings() {
        return bindingEntries;
    }

    Map<String, String> actionCommands() {
        return actionToCommand;
    }

    Map<Plugin, CopyOnWriteArrayList<KrepapiVersionPolicy.Constraint>> constraints() {
        return constraintsByPlugin;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            KrepapiVersionRequirement.parse(getConfig().getString("minimum-mod-version", "1.0").trim());
        } catch (IllegalArgumentException ex) {
            getLogger().severe("Invalid minimum-mod-version in config.yml: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadBindingConfig();
        openDebugFile();
        registerChannels();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, KrepapiChannels.C2S_CLIENT_INFO, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, KrepapiChannels.C2S_KEY_ACTION, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, KrepapiChannels.C2S_RAW_KEY, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, KrepapiChannels.C2S_MOUSE_ACTION, this);
        getCommand("krepapi").setExecutor(new KrepAPICommand(this));
        pluginUpdateService = new KrepAPIPluginUpdateService(this);
        pluginUpdateService.start();
        getLogger().info("KrepAPI enabled; " + bindingEntries.size() + " binding(s) loaded."
                + (isDebug() ? " ([KrepAPI-Debug] ON)" : ""));
    }

    @Override
    public void onDisable() {
        if (pluginUpdateService != null) {
            pluginUpdateService.stop();
            pluginUpdateService = null;
        }
        closeDebugFile();
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KrepapiChannels.C2S_CLIENT_INFO);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KrepapiChannels.C2S_KEY_ACTION);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KrepapiChannels.C2S_RAW_KEY);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, KrepapiChannels.C2S_MOUSE_ACTION);
        unregisterOutgoing();
        constraintsByPlugin.clear();
        clientCapabilities.clear();
        lastBindingCommandNanos.clear();
    }

    /** Re-reads bindings from config (called by {@code /krepapi reload}). */
    void reloadBindings() {
        loadBindingConfig();
        debug("Bindings reloaded: " + bindingEntries.size() + " binding(s)");
    }

    /**
     * Version requirements API for other plugins (constraints cleared when {@code plugin} disables).
     */
    public KrepAPIVersionGate versionGate(@NotNull Plugin plugin) {
        return new KrepAPIVersionGate(this, plugin);
    }

    void registerVersionConstraint(@NotNull Plugin owner, @NotNull KrepapiVersionPolicy.Constraint constraint) {
        try {
            KrepapiVersionRequirement.parse(constraint.minimumBuildVersion().trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid KrepAPI version requirement for " + owner.getName() + ": "
                    + ex.getMessage(), ex);
        }
        constraintsByPlugin.computeIfAbsent(owner, p -> new CopyOnWriteArrayList<>()).add(constraint);
        debug("registerVersionConstraint: " + owner.getName() + " -> "
                + (constraint.featureId() != null ? constraint.featureId() + " " : "")
                + ">= " + constraint.minimumBuildVersion());
        debugJson("version_constraint_registered",
                "\"owner\":" + KrepapiServerDebug.jsonString(owner.getName())
                        + ",\"minimumBuildVersion\":" + KrepapiServerDebug.jsonString(constraint.minimumBuildVersion())
                        + (constraint.featureId() != null
                        ? ",\"featureId\":" + KrepapiServerDebug.jsonString(constraint.featureId()) : ""));
    }

    private List<KrepapiVersionPolicy.Constraint> snapshotConstraints() {
        return constraintsByPlugin.values().stream().flatMap(List::stream).toList();
    }

    private void registerChannels() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, KrepapiChannels.S2C_HELLO);
        getServer().getMessenger().registerOutgoingPluginChannel(this, KrepapiChannels.S2C_BINDINGS);
        getServer().getMessenger().registerOutgoingPluginChannel(this, KrepapiChannels.S2C_RAW_CAPTURE);
        getServer().getMessenger().registerOutgoingPluginChannel(this, KrepapiChannels.S2C_INTERCEPT_KEYS);
        getServer().getMessenger().registerOutgoingPluginChannel(this, KrepapiChannels.S2C_MOUSE_CAPTURE);
        debug("Outgoing plugin channels registered: HELLO, BINDINGS, RAW_CAPTURE, INTERCEPT_KEYS, MOUSE_CAPTURE");
    }

    private void unregisterOutgoing() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KrepapiChannels.S2C_HELLO);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KrepapiChannels.S2C_BINDINGS);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KrepapiChannels.S2C_RAW_CAPTURE);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KrepapiChannels.S2C_INTERCEPT_KEYS);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, KrepapiChannels.S2C_MOUSE_CAPTURE);
    }

    /**
     * Sends {@link KrepapiChannels#S2C_RAW_CAPTURE} using the shared binary layout from {@link ProtocolMessages}.
     */
    public void sendRawCaptureConfig(@NotNull Player player, @NotNull ProtocolMessages.RawCaptureConfig config) {
        if (!player.isOnline()) {
            debug("sendRawCaptureConfig: " + player.getName() + " offline, skipped");
            return;
        }
        byte[] payload = ProtocolMessages.encodeRawCaptureConfig(config);
        debugPayload("S2C_RAW_CAPTURE -> " + player.getName(), payload);
        player.sendPluginMessage(this, KrepapiChannels.S2C_RAW_CAPTURE, payload);
    }

    /**
     * Sends {@link KrepapiChannels#S2C_INTERCEPT_KEYS}. An empty entry list clears intercept rules on the client.
     */
    public void sendInterceptKeys(@NotNull Player player, @NotNull ProtocolMessages.InterceptKeysSync sync) {
        if (!player.isOnline()) {
            debug("sendInterceptKeys: " + player.getName() + " offline, skipped");
            return;
        }
        byte[] payload = ProtocolMessages.encodeInterceptKeysSync(sync);
        debugPayload("S2C_INTERCEPT_KEYS -> " + player.getName(), payload);
        player.sendPluginMessage(this, KrepapiChannels.S2C_INTERCEPT_KEYS, payload);
    }

    /**
     * Sends {@link KrepapiChannels#S2C_MOUSE_CAPTURE} if the client advertised {@link KrepapiCapabilities#SERVER_MOUSE_CAPTURE}.
     */
    public void sendMouseCaptureConfig(@NotNull Player player, @NotNull ProtocolMessages.MouseCaptureConfig config) {
        if (!player.isOnline()) {
            debug("sendMouseCaptureConfig: " + player.getName() + " offline, skipped");
            return;
        }
        int caps = clientCapabilities.getOrDefault(player.getUniqueId(), 0);
        if ((caps & KrepapiCapabilities.SERVER_MOUSE_CAPTURE) == 0) {
            debug("sendMouseCaptureConfig: " + player.getName() + " lacks MOUSE_CAPTURE capability (caps=0x"
                    + Integer.toHexString(caps) + "), skipped");
            return;
        }
        byte[] payload = ProtocolMessages.encodeMouseCaptureConfig(config);
        debugPayload("S2C_MOUSE_CAPTURE -> " + player.getName(), payload);
        player.sendPluginMessage(this, KrepapiChannels.S2C_MOUSE_CAPTURE, payload);
    }

    /**
     * Capability bitfield from the player's last successful {@code c2s_client_info}, or {@code 0} if unknown.
     */
    public int getClientCapabilities(@NotNull Player player) {
        return clientCapabilities.getOrDefault(player.getUniqueId(), 0);
    }

    /**
     * Reads {@code bindings.*} into {@link ProtocolMessages.BindingEntry} rows and the action-id → command map.
     */
    private void loadBindingConfig() {
        List<ProtocolMessages.BindingEntry> entries = new ArrayList<>();
        Map<String, String> commands = new HashMap<>();
        ConfigurationSection root = getConfig().getConfigurationSection("bindings");
        if (root == null) {
            debug("loadBindingConfig: no 'bindings' section in config");
            bindingEntries = List.of();
            actionToCommand = Map.of();
            return;
        }
        int count = 0;
        for (String actionId : root.getKeys(false)) {
            if (count >= ProtocolMessages.MAX_BINDING_ENTRIES) {
                getLogger().severe("bindings: exceeded " + ProtocolMessages.MAX_BINDING_ENTRIES + "; ignoring remaining keys");
                break;
            }
            ConfigurationSection row = root.getConfigurationSection(actionId);
            if (row == null) {
                getLogger().severe("bindings." + actionId + ": expected a mapping, skipped");
                continue;
            }
            if (!row.contains("key") || !row.contains("display-name") || !row.contains("command")) {
                getLogger().severe("bindings." + actionId + ": requires key, display-name, and command; skipped");
                continue;
            }
            int key = row.getInt("key");
            String displayName = row.getString("display-name", "");
            String command = row.getString("command", "").trim();
            if (command.isEmpty()) {
                getLogger().severe("bindings." + actionId + ": command is empty; skipped");
                continue;
            }
            boolean overrideVanilla = row.getBoolean("override-vanilla", false);
            String category = row.getString("category", "server");
            if (!utf8Within(actionId, ProtocolMessages.MAX_ACTION_ID_UTF8_BYTES, "bindings." + actionId + " (action id)")) {
                continue;
            }
            if (!utf8Within(displayName, ProtocolBuf.MAX_STRING, "bindings." + actionId + ".display-name")) {
                continue;
            }
            if (!utf8Within(category, ProtocolMessages.MAX_CATEGORY_UTF8_BYTES, "bindings." + actionId + ".category")) {
                continue;
            }
            entries.add(new ProtocolMessages.BindingEntry(actionId, displayName, key, overrideVanilla, category));
            commands.put(actionId, command);
            debug("loadBindingConfig: " + actionId + " -> key=" + key + " cmd=/" + command
                    + " override=" + overrideVanilla + " cat=" + category);
            count++;
        }
        bindingEntries = List.copyOf(entries);
        actionToCommand = Map.copyOf(commands);
        debug("loadBindingConfig: " + entries.size() + " binding(s) loaded");
        debugJson("bindings_loaded", "\"count\":" + entries.size());
    }

    private boolean utf8Within(String value, int maxBytes, String label) {
        int n = value.getBytes(StandardCharsets.UTF_8).length;
        if (n > maxBytes) {
            getLogger().severe(label + ": UTF-8 length " + n + " exceeds maximum " + maxBytes);
            return false;
        }
        return true;
    }

    /**
     * Pushes {@link KrepapiChannels#S2C_BINDINGS} after handshake rules allow it (or when mod is optional and delay elapsed).
     */
    private void sendConfiguredBindings(Player player) {
        if (!player.isOnline()) {
            debug("sendConfiguredBindings: " + player.getName() + " offline, skipped");
            return;
        }
        PendingHandshake h = pending.get(player.getUniqueId());
        if (getConfig().getBoolean("require-krepapi", true) && (h == null || !h.answered)) {
            debug("sendConfiguredBindings: " + player.getName() + " handshake incomplete, skipped");
            return;
        }
        try {
            byte[] payload = ProtocolMessages.encodeBindingsSync(new ProtocolMessages.BindingsSync(bindingEntries));
            debugPayload("S2C_BINDINGS -> " + player.getName() + " (" + bindingEntries.size() + " entries)", payload);
            player.sendPluginMessage(this, KrepapiChannels.S2C_BINDINGS, payload);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Could not send bindings to " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (constraintsByPlugin.remove(event.getPlugin()) != null) {
            debug("onPluginDisable: cleared version constraints from " + event.getPlugin().getName());
            debugJson("version_constraints_cleared", "\"plugin\":" + KrepapiServerDebug.jsonString(event.getPlugin().getName()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long nonce = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        byte flags = getConfig().getBoolean("require-krepapi", true)
                ? ProtocolMessages.HELLO_FLAG_REQUIRE_RESPONSE
                : 0;
        String configMin = getConfig().getString("minimum-mod-version", "1.0");
        List<KrepapiVersionPolicy.Constraint> snap = snapshotConstraints();
        try {
            KrepapiVersionPolicy.validateRequirements(configMin, snap);
        } catch (IllegalArgumentException ex) {
            getLogger().severe("Invalid KrepAPI version requirements: " + ex.getMessage());
            player.kick(Component.text("KrepAPI server version requirements are misconfigured."));
            return;
        }
        String effectiveMin = KrepapiVersionPolicy.effectiveMinimum(configMin, snap);
        pending.put(player.getUniqueId(), new PendingHandshake(nonce, configMin, snap, false));

        debug("onJoin: " + player.getName() + " (uuid=" + player.getUniqueId() + ")"
                + " nonce=" + Long.toHexString(nonce) + " effectiveMin=" + effectiveMin
                + " flags=0x" + Integer.toHexString(flags & 0xFF)
                + " constraints=" + snap.size());
        debugJson("handshake_begin",
                "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                        + ",\"uuid\":\"" + player.getUniqueId() + "\""
                        + ",\"effectiveMin\":" + KrepapiServerDebug.jsonString(effectiveMin)
                        + ",\"flags\":" + (flags & 0xFF));

        byte[] payload = ProtocolMessages.encodeHello(new ProtocolMessages.Hello(
                KrepapiProtocolVersion.CURRENT,
                flags,
                effectiveMin,
                nonce
        ));
        debugPayload("S2C_HELLO -> " + player.getName(), payload);
        player.sendPluginMessage(this, KrepapiChannels.S2C_HELLO, payload);

        long delay = getConfig().getLong("handshake-timeout-ticks", 200L);
        if (getConfig().getBoolean("require-krepapi", true)) {
            debug("onJoin: scheduling handshake timeout for " + player.getName() + " in " + delay + " ticks");
            getServer().getScheduler().runTaskLater(this, () -> checkTimeout(player.getUniqueId()), delay);
        }

        if (!getConfig().getBoolean("require-krepapi", true)) {
            debug("onJoin: mod not required, scheduling binding sync for " + player.getName() + " in 40 ticks");
            getServer().getScheduler().runTaskLater(this, () -> sendConfiguredBindings(player), 40L);
        }

        long notifyDelay = getConfig().getLong("update-check.notify-delay-ticks", 80L);
        if (notifyDelay < 1L) {
            notifyDelay = 1L;
        }
        if (getConfig().getBoolean("update-check.enabled", true)
                && getConfig().getBoolean("update-check.notify-on-join", true)
                && pluginUpdateService != null) {
            UUID joinId = player.getUniqueId();
            getServer().getScheduler().runTaskLater(this, () -> {
                Player p = getServer().getPlayer(joinId);
                if (p != null && p.isOnline()) {
                    pluginUpdateService.maybeNotifyJoin(p);
                }
            }, notifyDelay);
        }
    }

    private void checkTimeout(UUID id) {
        PendingHandshake h = pending.get(id);
        if (h == null || h.answered) {
            debug("checkTimeout: " + id + " already answered or removed");
            return;
        }
        Player p = getServer().getPlayer(id);
        if (p != null && p.isOnline()) {
            debug("checkTimeout: kicking " + p.getName() + " (handshake timeout)");
            debugJson("kick_handshake_timeout",
                    "\"player\":" + KrepapiServerDebug.jsonString(p.getName()) + ",\"uuid\":\"" + id + "\"");
            p.kick(Component.text(KrepapiKickReasons.HANDSHAKE_TIMEOUT));
        }
        pending.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        debug("onQuit: " + event.getPlayer().getName() + " — clearing handshake, capabilities, cooldown");
        debugJson("disconnect",
                "\"player\":" + KrepapiServerDebug.jsonString(event.getPlayer().getName())
                        + ",\"uuid\":\"" + id + "\"");
        pending.remove(id);
        clientCapabilities.remove(id);
        lastBindingCommandNanos.remove(id);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        debugPayload("C2S " + channel + " <- " + player.getName(), message);
        if (KrepapiChannels.C2S_CLIENT_INFO.equals(channel)) {
            onClientInfo(player, message);
        } else if (KrepapiChannels.C2S_KEY_ACTION.equals(channel)) {
            onKeyAction(player, message);
        } else if (KrepapiChannels.C2S_RAW_KEY.equals(channel)) {
            onRawKey(player, message);
        } else if (KrepapiChannels.C2S_MOUSE_ACTION.equals(channel)) {
            onMouseAction(player, message);
        }
    }

    private void onClientInfo(Player player, byte[] message) {
        ProtocolMessages.ClientInfo info;
        try {
            info = ProtocolMessages.decodeClientInfo(message);
        } catch (RuntimeException ex) {
            getLogger().warning("Bad client_info from " + player.getName() + ": " + ex.getMessage());
            return;
        }
        debug("onClientInfo: " + player.getName()
                + " protocol=" + info.protocolVersion()
                + " modVersion=" + info.modVersion()
                + " caps=0x" + Integer.toHexString(info.capabilities())
                + " nonce=" + Long.toHexString(info.challengeNonce()));
        PendingHandshake h = pending.get(player.getUniqueId());
        if (h == null || h.nonce != info.challengeNonce()) {
            debug("onClientInfo: " + player.getName() + " nonce mismatch or no pending handshake"
                    + " (expected=" + (h != null ? Long.toHexString(h.nonce) : "none")
                    + " got=" + Long.toHexString(info.challengeNonce()) + ")");
            debugJson("handshake_rejected_nonce",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"uuid\":\"" + player.getUniqueId() + "\"");
            return;
        }
        h.answered = true;
        if (info.protocolVersion() != KrepapiProtocolVersion.CURRENT) {
            debug("onClientInfo: " + player.getName() + " protocol mismatch: client="
                    + info.protocolVersion() + " server=" + KrepapiProtocolVersion.CURRENT + ", kicking");
            debugJson("kick_protocol_mismatch",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"clientProtocol\":" + info.protocolVersion()
                            + ",\"serverProtocol\":" + KrepapiProtocolVersion.CURRENT);
            player.kick(Component.text(KrepapiKickReasons.PROTOCOL_MISMATCH));
            return;
        }
        KrepapiVersionPolicy.VersionCheckFailure fail = KrepapiVersionPolicy.firstVersionCheckFailure(
                info.modVersion(),
                h.configMin,
                h.constraintsSnapshot
        );
        if (fail != null) {
            debug("onClientInfo: " + player.getName() + " version check failed: "
                    + fail.reason() + " (mod=" + info.modVersion() + "), kicking");
            debugJson("kick_version_mismatch",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"modVersion\":" + KrepapiServerDebug.jsonString(info.modVersion())
                            + ",\"reason\":" + KrepapiServerDebug.jsonString(fail.reason()));
            player.kick(Component.text(KrepapiKickReasons.forVersionCheckFailure(fail)));
            return;
        }
        debug("onClientInfo: " + player.getName() + " handshake OK, capabilities stored");
        debugJson("handshake_complete",
                "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                        + ",\"uuid\":\"" + player.getUniqueId() + "\""
                        + ",\"modVersion\":" + KrepapiServerDebug.jsonString(info.modVersion())
                        + ",\"capabilities\":\"0x" + Integer.toHexString(info.capabilities()) + "\"");
        clientCapabilities.put(player.getUniqueId(), info.capabilities());
        getServer().getScheduler().runTaskLater(this, () -> sendConfiguredBindings(player), 1L);
    }

    private void onKeyAction(Player player, byte[] message) {
        try {
            ProtocolMessages.KeyAction a = ProtocolMessages.decodeKeyAction(message);
            debug("onKeyAction: " + player.getName() + " action=" + a.actionId()
                    + " phase=" + a.phase() + " seq=" + a.sequence());
            debugJson("key_action",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"uuid\":\"" + player.getUniqueId() + "\""
                            + ",\"actionId\":" + KrepapiServerDebug.jsonString(a.actionId())
                            + ",\"phase\":" + a.phase()
                            + ",\"seq\":" + a.sequence());
            if (a.phase() != ProtocolMessages.KeyAction.PHASE_PRESS) {
                debug("onKeyAction: " + player.getName() + " phase " + a.phase() + " ignored (not PRESS)");
                return;
            }
            String command = actionToCommand.get(a.actionId());
            if (command == null) {
                debug("onKeyAction: " + player.getName() + " unknown action '" + a.actionId() + "', ignored");
                return;
            }
            long now = System.nanoTime();
            long last = lastBindingCommandNanos.getOrDefault(player.getUniqueId(), Long.MIN_VALUE);
            if (now - last < BINDING_COOLDOWN_NANOS) {
                debug("onKeyAction: " + player.getName() + " cooldown active ("
                        + ((now - last) / 1_000_000) + "ms < 250ms), dropped");
                return;
            }
            lastBindingCommandNanos.put(player.getUniqueId(), now);
            final String stripped = command.startsWith("/") ? command.substring(1) : command;
            debug("onKeyAction: dispatching /" + stripped + " for " + player.getName());
            debugJson("key_command_dispatch",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"command\":" + KrepapiServerDebug.jsonString(stripped));
            getServer().getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    getServer().dispatchCommand(player, stripped);
                }
            });
        } catch (RuntimeException ex) {
            getLogger().warning("Bad key_action from " + player.getName());
        }
    }

    private void onRawKey(Player player, byte[] message) {
        try {
            var ev = ProtocolMessages.decodeRawKeyEvent(message);
            debug("onRawKey: " + player.getName() + " key=" + ev.key()
                    + " scancode=" + ev.scancode() + " glfwAction=" + ev.glfwAction()
                    + " modifiers=0x" + Integer.toHexString(ev.modifiers()));
            debugJson("raw_key",
                    "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                            + ",\"uuid\":\"" + player.getUniqueId() + "\""
                            + ",\"key\":" + ev.key()
                            + ",\"scancode\":" + ev.scancode()
                            + ",\"glfwAction\":" + ev.glfwAction()
                            + ",\"modifiers\":" + ev.modifiers()
                            + ",\"seq\":" + ev.sequence());
        } catch (RuntimeException ex) {
            getLogger().warning("Bad raw_key from " + player.getName());
        }
    }

    private void onMouseAction(Player player, byte[] message) {
        try {
            var ev = ProtocolMessages.decodeMouseAction(message);
            debug("onMouseAction: " + player.getName() + " kind=" + ev.kind()
                    + " button=" + ev.button() + " glfwAction=" + ev.glfwAction()
                    + " modifiers=0x" + Integer.toHexString(ev.modifiers()));
            if (ev.kind() == ProtocolMessages.MOUSE_ACTION_KIND_BUTTON) {
                String actionName = switch (ev.glfwAction()) {
                    case 0 -> "release";
                    case 1 -> "press";
                    default -> "unknown(" + ev.glfwAction() + ")";
                };
                debugJson("mouse_action",
                        "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                                + ",\"uuid\":\"" + player.getUniqueId() + "\""
                                + ",\"kind\":\"button\""
                                + ",\"button\":" + ev.button()
                                + ",\"glfwAction\":" + KrepapiServerDebug.jsonString(actionName)
                                + ",\"seq\":" + ev.sequence());
            } else if (ev.kind() == ProtocolMessages.MOUSE_ACTION_KIND_SCROLL) {
                debugJson("mouse_action",
                        "\"player\":" + KrepapiServerDebug.jsonString(player.getName())
                                + ",\"uuid\":\"" + player.getUniqueId() + "\""
                                + ",\"kind\":\"scroll\""
                                + ",\"scrollX\":" + ev.scrollDeltaX()
                                + ",\"scrollY\":" + ev.scrollDeltaY()
                                + ",\"seq\":" + ev.sequence());
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Bad mouse_action from " + player.getName());
        }
    }

    static final class PendingHandshake {
        final long nonce;
        final String configMin;
        final List<KrepapiVersionPolicy.Constraint> constraintsSnapshot;
        volatile boolean answered;

        PendingHandshake(
                long nonce,
                String configMin,
                List<KrepapiVersionPolicy.Constraint> constraintsSnapshot,
                boolean answered
        ) {
            this.nonce = nonce;
            this.configMin = configMin;
            this.constraintsSnapshot = constraintsSnapshot;
            this.answered = answered;
        }
    }
}
