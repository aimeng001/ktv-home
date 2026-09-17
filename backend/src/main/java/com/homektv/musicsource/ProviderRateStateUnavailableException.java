package com.homektv.musicsource;

/** Fail-closed error used when the persistent provider gate cannot be read or updated. */
public final class ProviderRateStateUnavailableException extends MusicSourceException {
    public ProviderRateStateUnavailableException(MusicProvider provider, Throwable cause) {
        super(provider, "音乐平台限速状态暂时不可用，已停止外部请求", cause);
    }
}
