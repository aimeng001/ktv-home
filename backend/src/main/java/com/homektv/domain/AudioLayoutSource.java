package com.homektv.domain;

/**
 * Provenance of the persisted audio layout.
 *
 * <p>Legacy rows are kept stable, automatic defaults may be recalculated when
 * media is probed, and explicit administrator changes always win over either
 * of those sources.</p>
 */
public enum AudioLayoutSource {
    LEGACY,
    AUTO_DEFAULT,
    MANUAL
}
