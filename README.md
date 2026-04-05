# KrepAPI-Plugin

**Run server commands when a player presses a key**—configured in a simple YAML file, no programming required.

This plugin is the “server side” of [KrepAPI](https://github.com/RafaelK-F/KrepAPI). It talks to the official **KrepAPI mod** on the player’s game client, registers your custom keys, and when someone presses a key you assigned, it runs the command **as that player** (same permissions as if they typed it in chat).

---

## Is this for me?

| You want… | This plugin |
|-----------|-------------|
| Extra keys that run `/menu`, `/warp shop`, etc. | Yes |
| A server where **everyone can join with a plain vanilla client** | Only if you turn off strict mod checks (see below); otherwise players need the **KrepAPI mod** |
| Something that works on **Spigot** only, or without Fabric + KrepAPI on the client | No—players need the matching **KrepAPI** mod for their Minecraft version |

---

## What you need

- **Paper** server, roughly **1.21.4 through 26.1.1** (one plugin JAR for that range; always **test on your exact Paper build** after updates).
- **Java** version required by **your** Paper download (often 21; newer Minecraft lines may ask for a newer Java—follow Paper’s download page).
- On each **player’s PC**: **Minecraft + Fabric + the KrepAPI mod** build that matches **their** game version (e.g. different mod files for 1.21.x vs 26.x—see the KrepAPI project).

**Straight talk:** we aim for one JAR across many Paper versions by using only common, stable APIs. If a future Paper update breaks something, you may need a plugin update—report it if that happens.

---

## Install (normal server owner)

1. Download **`KrepAPI-Plugin-*.jar`** (from releases or build it yourself—see below).
2. Put it in your server’s **`plugins`** folder.
3. Start the server once so **`config.yml`** is created.
4. Edit **`plugins/KrepAPI/config.yml`** (bindings, timeouts, etc.).
5. **Restart** the server so changes apply reliably (avoid `/reload` unless you know what you’re doing).

---

## Configure (`config.yml`)

**Global options**

| Setting | Default | In plain English |
|---------|---------|------------------|
| `require-krepapi` | `true` | If `true`, players **without** the mod (or who don’t finish setup in time) can be **kicked**. Set `false` only if you accept vanilla clients or incomplete handshakes. |
| `minimum-mod-version` | `"1.0"` | Lowest KrepAPI **mod** version you allow. Wrong syntax here **disables** the plugin at startup—check the server log. |
| `handshake-timeout-ticks` | `200` | How long to wait for the client to answer before kicking (20 ticks ≈ 1 second). |

**Per key: `bindings.<your-id>`**

| Setting | Default | In plain English |
|---------|---------|------------------|
| `key` | *(required)* | Keyboard code used by the game (GLFW). Many lists exist online (e.g. `71` is often **G**). |
| `display-name` | *(required)* | Name players see in the **Controls** screen. |
| `command` | *(required)* | Command that runs **when the key is pressed** (with or without `/`). Runs **as the player**, so permissions are the same as normal chat commands. |
| `override-vanilla` | `false` | If `true`, tries to stop Minecraft from using that key for its normal job (where the client supports it). |
| `category` | `server` | Group in the controls menu. |

**Example**

```yaml
bindings:
  open_menu:
    key: 71
    display-name: "Open server menu"
    override-vanilla: false
    category: server
    command: "menu"
```

Broken binding blocks are skipped and **logged** in the console. There is a **large upper limit** on how many bindings you can define (thousands); you won’t hit it on a normal server.

---

## Safety (read this)

- Keys only run commands you **listed in `config.yml`**. Random or forged packets can’t trigger other commands through this plugin.
- There is a **short cooldown** per player so repeated packets can’t spam commands.
- Still: a command runs **with that player’s permissions**. Don’t map a key to dangerous commands unless only trusted players have those permissions.

---

## Building from source (optional)

Only needed if you develop or don’t use a prebuilt JAR.

- You need the **[KrepAPI](https://github.com/RafaelK-F/KrepAPI)** source repo **next to** this folder (default: `../KrepAPI`) so Gradle can pull the shared **protocol** module.
- Use this project’s **Gradle wrapper** (currently **9.4.x**). Older Gradle (e.g. 8.8) fails when the composite build configures KrepAPI’s Fabric modules—Loom needs Gradle **9.2+**.
- Run `./gradlew build`. Output: `build/libs/KrepAPI-Plugin-1.0.0.jar`.
- The **`net.shik:protocol:…`** version in `build.gradle` must match **`mod_version`** in `KrepAPI/gradle.properties`.

---

## For plugin developers

The main class is `net.shik.krepapi.plugin.KrepAPIPlugin`. Plugin name in Bukkit is **`KrepAPI`**. Other plugins can add **extra** minimum client versions:

```java
Plugin k = getServer().getPluginManager().getPlugin("KrepAPI");
if (k instanceof KrepAPIPlugin krep) {
    krep.versionGate(this).requireMinimumBuildVersion("1.3.0");
}
```

Protocol and version syntax: see the [KrepAPI repo](https://github.com/RafaelK-F/KrepAPI) (`docs/protocol.md`, `docs/paper-plugin.md`). The source here is commented as a **template** for your own KrepAPI integrations.

---

## License / authors

See this repository and `plugin.yml` for credits and licensing.
