package com.homektv.ws;

/**
 * Published after a broadcaster detects a broken transport.  The player
 * removal is computed at the same time as the session removal so the handler
 * can promote a standby player without racing a later close callback.
 */
public record WsSessionDisconnectedEvent(
        String sessionId,
        String clientType,
        ActivePlayerRegistry.Unregistration playerRemoval) {
}
