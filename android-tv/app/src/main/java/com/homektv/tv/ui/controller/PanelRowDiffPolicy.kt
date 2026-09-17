package com.homektv.tv.ui.controller

internal object PanelRowDiffPolicy {
    fun areItemsTheSame(old: PanelRow, new: PanelRow): Boolean =
        old.stableId == new.stableId && old::class == new::class

    fun areContentsTheSame(old: PanelRow, new: PanelRow): Boolean = when {
        old is ArtistPanelRow && new is ArtistPanelRow -> old.artist == new.artist
        old is NamedCountPanelRow && new is NamedCountPanelRow -> old.item == new.item
        old is ActionPanelRow && new is ActionPanelRow ->
            old.id == new.id && old.text == new.text && old.enabled == new.enabled &&
                old.contentDescription == new.contentDescription &&
                old.actionKey == new.actionKey
        else -> old == new
    }
}
