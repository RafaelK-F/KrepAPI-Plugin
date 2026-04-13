package net.shik.krepapi.plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.shik.krepapi.protocol.KrepapiBuildVersion;

/**
 * Fetches {@code .system/plugin/versions.json} from the KrepAPI repo (same shape as the Fabric {@code UpdateChecker})
 * and notifies permitted players when a newer Paper plugin build exists. The click action opens the JAR URL in the
 * player’s browser — it does not install the file on the server.
 */
public final class KrepAPIPluginUpdateService {

    private static final String DEFAULT_VERSIONS_URL =
            "https://raw.githubusercontent.com/RafaelK-F/KrepAPI/main/.system/plugin/versions.json";
    private static final String DEFAULT_JAR_BASE_URL =
            "https://github.com/RafaelK-F/KrepAPI/raw/main/.system/plugin/jar/";
    private static final String DEFAULT_FALLBACK_URL = "https://github.com/RafaelK-F/KrepAPI";

    private final JavaPlugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final AtomicReference<PluginUpdateSnapshot> snapshot = new AtomicReference<>(PluginUpdateSnapshot.empty());

    private @Nullable BukkitTask intervalTask;

    KrepAPIPluginUpdateService(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        stop();
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("update-check.enabled", true)) {
            return;
        }
        runCheckAsync();
        long ticks = cfg.getLong("update-check.interval-minutes", 360L) * 60L * 20L;
        if (ticks < 20L * 60L) {
            ticks = 20L * 60L;
        }
        intervalTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runCheckAsync, ticks, ticks);
    }

    void stop() {
        if (intervalTask != null) {
            intervalTask.cancel();
            intervalTask = null;
        }
    }

    void refreshAfterConfigReload() {
        stop();
        start();
    }

    /** Called a few ticks after join for players who may receive update notices. */
    void maybeNotifyJoin(@NotNull Player player) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("update-check.enabled", true) || !cfg.getBoolean("update-check.notify-on-join", true)) {
            return;
        }
        if (!canReceiveUpdateNotice(player)) {
            return;
        }
        PluginUpdateSnapshot snap = snapshot.get();
        if (!snap.updateAvailable() || snap.latestVersion() == null) {
            return;
        }
        String url = snap.downloadUrl() != null ? snap.downloadUrl() : snap.fallbackUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        sendUpdateMessage(player, snap, url);
    }

    private static boolean canReceiveUpdateNotice(@NotNull Player player) {
        return player.hasPermission("krepapi.*") || player.hasPermission("krepapi.update.notify");
    }

    private void sendUpdateMessage(@NotNull Player player, @NotNull PluginUpdateSnapshot snap, @NotNull String openUrl) {
        Component line = Component.empty()
                .append(Component.text("╭ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("KrepAPI", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" · Update", NamedTextColor.GRAY))
                .append(Component.text(" ╮", NamedTextColor.DARK_GRAY));

        Component sub = Component.text(
                "A newer plugin build is available for your Minecraft version.",
                NamedTextColor.GRAY);

        String cur = snap.currentVersion() != null ? snap.currentVersion() : "?";
        String lat = snap.latestVersion() != null ? snap.latestVersion() : "?";
        Component verLine = Component.text("Installed: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(cur, NamedTextColor.RED))
                .append(Component.text("  →  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("Latest: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(lat, NamedTextColor.GREEN));

        Component click = Component.text(
                "[CLICK AND UPDATE]",
                Style.style()
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(openUrl))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Open download in browser\n" + openUrl, NamedTextColor.WHITE)))
                        .build());

        Component hint = Component.text(
                "Replace the JAR in plugins/ and restart the server. (Opens your browser.)",
                NamedTextColor.DARK_GRAY, TextDecoration.ITALIC);

        player.sendMessage(Component.empty());
        player.sendMessage(line);
        player.sendMessage(sub);
        player.sendMessage(verLine);
        player.sendMessage(click);
        player.sendMessage(hint);
        player.sendMessage(Component.empty());
    }

    private void runCheckAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PluginUpdateSnapshot next = fetchSnapshot();
            snapshot.set(next);
            if (next.updateAvailable() && next.latestVersion() != null) {
                plugin.getLogger().info("[Update] New KrepAPI-Plugin build available: " + next.latestVersion()
                        + " (running " + next.currentVersion() + ")");
            }
            if (plugin.getConfig().getBoolean("debug-logging", false)
                    && plugin.getConfig().getBoolean("debug-logging-verbose", false)) {
                plugin.getLogger().info("[KrepAPI-Debug] [Update] mc=" + next.minecraftVersion()
                        + " current=" + next.currentVersion()
                        + " latest=" + next.latestVersion()
                        + " updateAvailable=" + next.updateAvailable()
                        + " jarBaseName=" + next.jarBaseName()
                        + " downloadUrl=" + next.downloadUrl()
                        + " knownVersions=" + next.knownVersions().size()
                        + " err=" + next.lastError());
            }
        });
    }

    private @NotNull PluginUpdateSnapshot fetchSnapshot() {
        FileConfiguration cfg = plugin.getConfig();
        String versionsUrl = cfg.getString("update-check.versions-url", DEFAULT_VERSIONS_URL);
        String jarBase = cfg.getString("update-check.jar-download-base-url", DEFAULT_JAR_BASE_URL);
        String fallback = cfg.getString("update-check.fallback-page-url", DEFAULT_FALLBACK_URL);
        if (!jarBase.endsWith("/")) {
            jarBase = jarBase + "/";
        }

        String current = plugin.getPluginMeta().getVersion();
        String mcVersion = safeMcVersion();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(versionsUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                plugin.getLogger().log(Level.WARNING, "[Update] versions.json HTTP " + resp.statusCode());
                return PluginUpdateSnapshot.failed(current, "HTTP " + resp.statusCode());
            }
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonObject versions = root.getAsJsonObject("versions");
            String latest = versions.get("latest").getAsString();
            JsonObject list = versions.getAsJsonObject("list");

            String jarFileName = null;
            if (list.has(latest)) {
                JsonObject latestEntry = list.getAsJsonObject(latest);
                for (Map.Entry<String, JsonElement> entry : latestEntry.entrySet()) {
                    String[] mcVersions = entry.getKey().split(",");
                    for (String mcv : mcVersions) {
                        if (mcv.trim().equals(mcVersion)) {
                            jarFileName = entry.getValue().getAsString();
                            break;
                        }
                    }
                    if (jarFileName != null) {
                        break;
                    }
                }
            }

            String downloadUrl = jarFileName != null ? jarBase + jarFileName + ".jar" : null;
            boolean updateAvailable = KrepapiBuildVersion.compare(latest, current) > 0;

            List<String> versionList = new ArrayList<>(list.keySet());
            return new PluginUpdateSnapshot(current, latest, jarFileName, downloadUrl, fallback, updateAvailable, mcVersion, versionList, null);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Update] Check failed: " + e.getMessage());
            return PluginUpdateSnapshot.failed(current, e.getMessage());
        }
    }

    private @NotNull String safeMcVersion() {
        try {
            String v = Bukkit.getServer().getMinecraftVersion();
            return v != null ? v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Latest cached result from the remote {@code versions.json} (may be empty until the first check finishes). */
    @Nullable PluginUpdateSnapshot getSnapshot() {
        return snapshot.get();
    }

    public record PluginUpdateSnapshot(
            @Nullable String currentVersion,
            @Nullable String latestVersion,
            @Nullable String jarBaseName,
            @Nullable String downloadUrl,
            @Nullable String fallbackUrl,
            boolean updateAvailable,
            @Nullable String minecraftVersion,
            @NotNull List<String> knownVersions,
            @Nullable String lastError
    ) {
        static @NotNull PluginUpdateSnapshot empty() {
            return new PluginUpdateSnapshot(null, null, null, null, null, false, null, List.of(), null);
        }

        static @NotNull PluginUpdateSnapshot failed(@Nullable String current, @Nullable String err) {
            return new PluginUpdateSnapshot(current, null, null, null, DEFAULT_FALLBACK_URL, false, null, List.of(), err);
        }
    }
}
