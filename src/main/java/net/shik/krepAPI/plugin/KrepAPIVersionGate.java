package net.shik.krepapi.plugin;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import net.shik.krepapi.protocol.KrepapiVersionPolicy;

/**
 * Registers additional minimum KrepAPI client build versions for the calling plugin.
 * Registrations are removed automatically when the plugin disables.
 */
public final class KrepAPIVersionGate {

    private final KrepAPIPlugin krepapi;
    private final Plugin owner;

    KrepAPIVersionGate(KrepAPIPlugin krepapi, Plugin owner) {
        this.krepapi = krepapi;
        this.owner = owner;
    }

    /**
     * Add a global client build requirement (in addition to {@code config.yml} and other plugins). The {@code semver}
     * argument is a {@link net.shik.krepapi.protocol.KrepapiVersionRequirement} expression (e.g. {@code 1.2.0} for
     * {@code >= 1.2.0}, {@code 1.1.x}, {@code <2.0.0}, {@code =1.2.0}). Invalid expressions throw when registering.
     */
    public void requireMinimumBuildVersion(@NotNull String semver) {
        krepapi.registerVersionConstraint(owner, KrepapiVersionPolicy.Constraint.global(semver));
    }

    /**
     * Same as {@link #requireMinimumBuildVersion(String)} but associates the constraint with a feature id for kick messages.
     */
    public void requireMinimumBuildVersionForFeature(@NotNull String featureId, @NotNull String semver) {
        krepapi.registerVersionConstraint(owner, KrepapiVersionPolicy.Constraint.feature(featureId, semver));
    }
}
