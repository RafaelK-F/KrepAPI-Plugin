package net.shik.krepapi.plugin;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.shik.krepapi.protocol.KrepapiCapabilities;
import net.shik.krepapi.protocol.KrepapiServerDebug;
import net.shik.krepapi.protocol.ProtocolMessages;

/**
 * {@code /krepapi} admin command: settings (persisted to {@code config.yml} where applicable), status, reload.
 */
final class KrepAPICommand implements TabExecutor {

    private static final String PERM = "krepapi.admin";

    private static final List<String> SUBS = List.of(
            "debug", "status", "reload", "require-mod", "min-version", "handshake-timeout");

    private final KrepAPIPlugin plugin;

    KrepAPICommand(KrepAPIPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "debug" -> handleDebug(sender, args);
            case "status" -> handleStatus(sender, args);
            case "reload" -> handleReload(sender);
            case "require-mod" -> handleRequireMod(sender, args);
            case "min-version" -> handleMinVersion(sender, args);
            case "handshake-timeout" -> handleHandshakeTimeout(sender, args);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    // ── /krepapi debug ──────────────────────────────────────────────────

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String mode = args[1].toLowerCase();
            if (mode.equals("on") || mode.equals("true")) {
                plugin.saveDebugLoggingToConfig(true);
                sender.sendMessage(Component.text(
                        "config.yml: debug-logging=true (saved). JVM/marker can still force debug ON.",
                        NamedTextColor.GREEN));
                return;
            }
            if (mode.equals("off") || mode.equals("false")) {
                plugin.saveDebugLoggingToConfig(false);
                sender.sendMessage(Component.text(
                        "config.yml: debug-logging=false (saved). JVM/marker can still force debug ON.",
                        NamedTextColor.GREEN));
                return;
            }
            if (mode.equals("toggle")) {
                boolean cur = plugin.getConfig().getBoolean("debug-logging", false)
                        || plugin.getConfig().getBoolean("debug", false);
                plugin.saveDebugLoggingToConfig(!cur);
                sender.sendMessage(Component.text(
                        "config.yml: debug-logging=" + !cur + " (saved).",
                        NamedTextColor.GREEN));
                return;
            }
            sender.sendMessage(Component.text("Usage: /krepapi debug [on|off|toggle]", NamedTextColor.RED));
            return;
        }
        boolean cfg = plugin.getConfig().getBoolean("debug-logging", false)
                || plugin.getConfig().getBoolean("debug", false);
        boolean jvm = "true".equalsIgnoreCase(System.getProperty(KrepapiServerDebug.JVM_PROPERTY));
        boolean marker = new java.io.File(KrepapiServerDebug.MARKER_FILENAME).exists();
        boolean active = plugin.isKrepapiDebugActive();
        sender.sendMessage(Component.text("KrepAPI debug (same activation model as KrepAPI Paper + Fabric server):",
                NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  Effective: " + (active ? "ON" : "OFF"),
                active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  config debug-logging (or legacy debug): " + cfg, NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  config debug-logging-verbose: "
                + plugin.getConfig().getBoolean("debug-logging-verbose", false), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  JVM -D" + KrepapiServerDebug.JVM_PROPERTY + "=true: " + jvm,
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  marker file ./" + KrepapiServerDebug.MARKER_FILENAME + ": " + marker,
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "  When ON: NDJSON in plugins/" + plugin.getName() + "/debug-<time>.json; verbose adds per-message hex, thread, session_start, decode errors.",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  Change file: /krepapi debug on|off|toggle", NamedTextColor.DARK_GRAY));
    }

    private void handleRequireMod(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /krepapi require-mod <on|off|toggle>", NamedTextColor.RED));
            return;
        }
        String mode = args[1].toLowerCase();
        boolean cur = plugin.getConfig().getBoolean("require-krepapi", true);
        boolean next;
        if (mode.equals("on") || mode.equals("true")) {
            next = true;
        } else if (mode.equals("off") || mode.equals("false")) {
            next = false;
        } else if (mode.equals("toggle")) {
            next = !cur;
        } else {
            sender.sendMessage(Component.text("Usage: /krepapi require-mod <on|off|toggle>", NamedTextColor.RED));
            return;
        }
        plugin.saveRequireKrepapiToConfig(next);
        sender.sendMessage(Component.text("config.yml: require-krepapi=" + next + " (saved).", NamedTextColor.GREEN));
    }

    private void handleMinVersion(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /krepapi min-version <version>", NamedTextColor.RED));
            return;
        }
        String ver = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        try {
            plugin.saveMinimumModVersionToConfig(ver);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text("Invalid version: " + ex.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("config.yml: minimum-mod-version=\"" + ver + "\" (saved).",
                NamedTextColor.GREEN));
    }

    private void handleHandshakeTimeout(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /krepapi handshake-timeout <ticks>", NamedTextColor.RED));
            return;
        }
        long ticks;
        try {
            ticks = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.text("Not a number: " + args[1], NamedTextColor.RED));
            return;
        }
        try {
            plugin.saveHandshakeTimeoutTicks(ticks);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(Component.text(ex.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("config.yml: handshake-timeout-ticks=" + ticks + " (saved).",
                NamedTextColor.GREEN));
    }

    // ── /krepapi status [player] ────────────────────────────────────────

    private void handleStatus(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found: " + args[1], NamedTextColor.RED));
                return;
            }
            showPlayerStatus(sender, target);
            return;
        }
        showGlobalStatus(sender);
    }

    private void showGlobalStatus(CommandSender sender) {
        sender.sendMessage(Component.text("--- KrepAPI Status ---", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Debug: " + (plugin.isKrepapiDebugActive() ? "ON" : "OFF"),
                NamedTextColor.YELLOW));
        if (plugin.isKrepapiDebugActive()) {
            sender.sendMessage(Component.text(
                    "  Verbose (extra NDJSON + chunked hex + thread field): "
                            + (plugin.isDebugVerbose() ? "ON" : "OFF"),
                    NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("Require mod: " + plugin.getConfig().getBoolean("require-krepapi", true),
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Min mod version: "
                + plugin.getConfig().getString("minimum-mod-version", "1.0"), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Handshake: send delay "
                + plugin.getConfig().getLong("handshake-send-delay-ticks", 0L) + " ticks, timeout "
                + plugin.getConfig().getLong("handshake-timeout-ticks", 200L) + " ticks after hello",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Bindings loaded: " + plugin.bindings().size(), NamedTextColor.YELLOW));
        for (ProtocolMessages.BindingEntry b : plugin.bindings()) {
            sender.sendMessage(Component.text("  " + b.actionId() + " -> key " + b.defaultKey()
                    + " -> /" + plugin.actionCommands().getOrDefault(b.actionId(), "?"), NamedTextColor.AQUA));
        }

        int handshaked = 0;
        int pendingCount = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (plugin.capabilities().containsKey(id)) {
                handshaked++;
            } else if (plugin.pendingHandshakes().containsKey(id)) {
                pendingCount++;
            }
        }
        sender.sendMessage(Component.text("Online: " + Bukkit.getOnlinePlayers().size()
                + "  Handshaked: " + handshaked + "  Pending: " + pendingCount, NamedTextColor.YELLOW));

        if (!plugin.constraints().isEmpty()) {
            sender.sendMessage(Component.text("Version constraints:", NamedTextColor.YELLOW));
            plugin.constraints().forEach((pl, list) ->
                    list.forEach(c -> sender.sendMessage(Component.text("  " + pl.getName() + ": "
                            + (c.featureId() != null ? c.featureId() + " " : "") + ">= " + c.minimumBuildVersion(),
                            NamedTextColor.AQUA))));
        }

        var ups = plugin.getPluginUpdateSnapshot();
        if (ups != null && plugin.getConfig().getBoolean("update-check.enabled", true)) {
            if (ups.lastError() != null) {
                sender.sendMessage(Component.text("Plugin update check: failed (" + ups.lastError() + ")",
                        NamedTextColor.RED));
            } else {
                sender.sendMessage(Component.text(
                        "Plugin JAR: " + (ups.currentVersion() != null ? ups.currentVersion() : "?")
                                + "  Remote latest: " + (ups.latestVersion() != null ? ups.latestVersion() : "?")
                                + "  MC: " + (ups.minecraftVersion() != null ? ups.minecraftVersion() : "?"),
                        NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(
                        "Update available: " + ups.updateAvailable(),
                        ups.updateAvailable() ? NamedTextColor.GOLD : NamedTextColor.GREEN));
            }
        }
    }

    private void showPlayerStatus(CommandSender sender, Player target) {
        UUID id = target.getUniqueId();
        sender.sendMessage(Component.text("--- " + target.getName() + " ---", NamedTextColor.GOLD));

        Integer caps = plugin.capabilities().get(id);
        if (caps != null) {
            sender.sendMessage(Component.text("Handshake: complete", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("Capabilities: 0x" + Integer.toHexString(caps), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "  MOUSE_CAPTURE=" + ((caps & KrepapiCapabilities.SERVER_MOUSE_CAPTURE) != 0),
                    NamedTextColor.AQUA));
        } else {
            var hs = plugin.pendingHandshakes().get(id);
            if (hs != null) {
                sender.sendMessage(Component.text("Handshake: pending (answered=" + hs.answered + ")",
                        NamedTextColor.YELLOW));
            } else {
                sender.sendMessage(Component.text("Handshake: none (no mod or not joined through KrepAPI)",
                        NamedTextColor.RED));
            }
        }
    }

    // ── /krepapi reload ─────────────────────────────────────────────────

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.refreshDebugLoggingAfterConfigReload();
        plugin.reloadBindings();
        plugin.refreshPluginUpdateAfterReload();
        sender.sendMessage(Component.text("KrepAPI config reloaded; " + plugin.bindings().size() + " binding(s)."
                + (plugin.isKrepapiDebugActive() ? " (debug ON)" : ""), NamedTextColor.GREEN));
    }

    // ── Tab completion ──────────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM)) {
            return List.of();
        }
        if (args.length == 1) {
            return SUBS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("debug") || sub.equals("require-mod")) {
                return List.of("on", "off", "toggle").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (sub.equals("status")) {
                return null; // default player name completion
            }
        }
        return List.of();
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " debug [on|off|toggle]", NamedTextColor.YELLOW)
                .append(Component.text(" - Status or persist debug-logging to config.yml", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " require-mod <on|off|toggle>", NamedTextColor.YELLOW)
                .append(Component.text(" - Persist require-krepapi", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " min-version <version>", NamedTextColor.YELLOW)
                .append(Component.text(" - Persist minimum-mod-version", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " handshake-timeout <ticks>", NamedTextColor.YELLOW)
                .append(Component.text(" - Persist handshake-timeout-ticks", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " status [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Show plugin/player status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload config.yml from disk", NamedTextColor.GRAY)));
    }
}
