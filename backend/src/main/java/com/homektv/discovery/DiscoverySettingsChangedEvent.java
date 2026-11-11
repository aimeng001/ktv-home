package com.homektv.discovery;

/** Settings snapshot emitted after a discovery configuration transaction commits. */
public record DiscoverySettingsChangedEvent(boolean enabled, String name) {
}
