package net.shik.krepapi.plugin;

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
import net.shik.krepapi.protocol.ProtocolMessages;

/**
 * {@code /krepapi} admin command: debug toggle, runtime status, config reload.
 */
final class KrepAPICommand implements TabExecutor {

    private static final String PERM = "krepapi.admin";

    private static final List<String> SUBS = List.of("debug", "status", "reload");

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
            default -> sendUsage(sender, label);
        }
        return true;
    }

    // ── /krepapi debug [on|off] ─────────────────────────────────────────

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            boolean on = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
            plugin.setDebugEnabled(on);
        } else {
            plugin.setDebugEnabled(!plugin.isDebugEnabled());
        }
        sender.sendMessage(Component.text(
                "KrepAPI debug: " + (plugin.isDebugEnabled() ? "ON" : "OFF"),
                plugin.isDebugEnabled() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
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
        sender.sendMessage(Component.text("Debug: " + (plugin.isDebugEnabled() ? "ON" : "OFF"), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Require mod: " + plugin.getConfig().getBoolean("require-krepapi", true),
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Min mod version: "
                + plugin.getConfig().getString("minimum-mod-version", "1.0"), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Bindings loaded: " + plugin.bindings().size(), NamedTextColor.YELLOW));
        for (ProtocolMessages.BindingEntry b : plugin.bindings()) {
            sender.sendMessage(Component.text("  " + b.actionId() + " -> key " + b.key()
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
        plugin.setDebugEnabled(plugin.getConfig().getBoolean("debug", false));
        plugin.reloadBindings();
        sender.sendMessage(Component.text("KrepAPI config reloaded; " + plugin.bindings().size() + " binding(s)."
                + (plugin.isDebugEnabled() ? " (debug ON)" : ""), NamedTextColor.GREEN));
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
            if (args[0].equalsIgnoreCase("debug")) {
                return List.of("on", "off").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            }
            if (args[0].equalsIgnoreCase("status")) {
                return null; // default player name completion
            }
        }
        return List.of();
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(Component.text("/" + label + " debug [on|off]", NamedTextColor.YELLOW)
                .append(Component.text(" - Toggle debug logging", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " status [player]", NamedTextColor.YELLOW)
                .append(Component.text(" - Show plugin/player status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload config.yml", NamedTextColor.GRAY)));
    }
}
