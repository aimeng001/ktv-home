package com.homektv.web.dto;

/**
 * 管理后台仪表盘数据（P2.1，详设§8 ADM-01）。
 *
 * Admin dashboard data (P2.1, detailed design §8 ADM-01).
 */
public record DashboardDto(
        long totalSongs,
        long ktvCount,
        long mvCount,
        long audioCount,
        long unrecognizedCount,
        long totalPlays,
        int connectedClients,
        String playerState,
        String nowPlayingTitle,
        long externalIndexedFiles
) {
    public DashboardDto(long totalSongs, long ktvCount, long mvCount, long audioCount,
                        long unrecognizedCount, long totalPlays, int connectedClients,
                        String playerState, String nowPlayingTitle) {
        this(totalSongs, ktvCount, mvCount, audioCount, unrecognizedCount,
                totalPlays, connectedClients, playerState, nowPlayingTitle, 0L);
    }
}
