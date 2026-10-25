using System.Text.Json;
using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;
using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackCoordinatorTests
{
    [Fact]
    public async Task Preparing_playback_uses_ready_variant_identity_and_audio_semantics()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 10);
        var api = new ResolvingServerApi(
            source,
            new PlaybackDescriptor(
                "TRANSCODE", "READY", 10, 88,
                "http://server/api/playback/stream/88", 2, 1,
                new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1), null, null));
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.Zero);
        var preparing = new PlaybackDescriptor("TRANSCODE", "PREPARING", 10, 88);

        await coordinator.ApplySnapshotAsync(
            "sync_full", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK, playback: preparing));

        Assert.Equal(new long[] { 88 }, output.LoadedFileIds);
        Assert.Equal(new[] { 1 }, output.AudioTrackIndices);
        Assert.Equal("http://server/api/playback/stream/88", output.LoadedStreamUrls.Single());
    }

    [Fact]
    public async Task Live_transcode_uses_source_identity_and_pipe_url_without_variant_id()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 31);
        var api = new ResolvingServerApi(
            source,
            new PlaybackDescriptor(
                "LIVE_TRANSCODE", "READY", 31, null,
                "http://server/api/stream/31?transcode=true&start=65.250", 2, 1,
                new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1), null, null));
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.Zero);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("accompaniment"));

        Assert.Equal(new long[] { 31 }, output.LoadedFileIds);
        Assert.Equal("http://server/api/stream/31?transcode=true&start=65.250", output.LoadedStreamUrls.Single());
        Assert.Equal(new[] { 1 }, output.AudioTrackIndices);
    }

    [Fact]
    public async Task Live_pipe_recovery_and_seek_reopen_at_absolute_positions_without_changing_queue_identity()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 31);
        var descriptor = new PlaybackDescriptor(
            "LIVE_TRANSCODE", "READY", 31, null,
            "http://server/api/stream/31?token=local&transcode=true", 2, 1,
            new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1), null, null);
        var coordinator = new PlaybackCoordinator(
            new ResolvingServerApi(source, descriptor), output, TimeSpan.Zero);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot(
            "accompaniment", queueId: 41, state: "paused", positionMs: 60_000,
            seekSequence: 3, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));
        var firstOutput = Assert.IsType<ActiveOutputIdentity>(coordinator.ActiveOutput);

        await coordinator.ApplySnapshotAsync("playback_seeked", Snapshot(
            "accompaniment", queueId: 41, state: "paused", positionMs: 120_000,
            seekSequence: 4, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));

        var restartedOutput = Assert.IsType<ActiveOutputIdentity>(coordinator.ActiveOutput);
        Assert.Equal(new long[] { 31, 31 }, output.LoadedFileIds);
        Assert.Equal(new[]
        {
            "http://server/api/stream/31?token=local&transcode=true&start=60.000",
            "http://server/api/stream/31?token=local&transcode=true&start=120.000",
        }, output.LoadedStreamUrls);
        Assert.Equal(1, output.StopCount);
        Assert.Empty(output.SeekPositions);
        Assert.Equal(firstOutput.QueueId, restartedOutput.QueueId);
        Assert.Equal(firstOutput.FileId, restartedOutput.FileId);
        Assert.True(restartedOutput.Generation > firstOutput.Generation);
        Assert.Equal(new[] { 1, 1 }, output.AudioTrackIndices);
        Assert.Equal(new[] { (60, false), (60, false) }, output.VolumeChanges);
        Assert.Equal(2, output.PauseCount);
    }

    [Fact]
    public async Task Live_pipe_pause_resume_and_vocal_switch_do_not_reopen_or_seek()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 31);
        var descriptor = new PlaybackDescriptor(
            "LIVE_TRANSCODE", "READY", 31, null,
            "http://server/api/stream/31?token=local&transcode=true", 2, 1,
            new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1), null, null);
        var coordinator = new PlaybackCoordinator(
            new ResolvingServerApi(source, descriptor), output, TimeSpan.Zero);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot(
            "original", queueId: 41, state: "playing", positionMs: 60_000,
            seekSequence: 3, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));
        var active = Assert.IsType<ActiveOutputIdentity>(coordinator.ActiveOutput);
        await coordinator.ApplySnapshotAsync("player_state", Snapshot(
            "original", queueId: 41, state: "paused", positionMs: 60_000,
            seekSequence: 3, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));
        await coordinator.ApplySnapshotAsync("player_state", Snapshot(
            "original", queueId: 41, state: "playing", positionMs: 60_000,
            seekSequence: 3, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));
        await coordinator.ApplySnapshotAsync("vocal_changed", Snapshot(
            "accompaniment", queueId: 41, state: "playing", positionMs: 60_000,
            seekSequence: 3, audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));

        Assert.Equal(1, output.LoadCount);
        Assert.Equal(0, output.StopCount);
        Assert.Empty(output.SeekPositions);
        Assert.Equal(new[] { 0, 1 }, output.AudioTrackIndices);
        Assert.Equal(new[] { (60, false) }, output.VolumeChanges);
        Assert.Equal(active, coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Preparing_variant_that_disappears_falls_back_to_the_ready_native_source()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 10);
        var api = new ResolvingServerApi(source, null);
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.Zero);

        await coordinator.ApplySnapshotAsync(
            "sync_full",
            Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK,
                playback: new PlaybackDescriptor("TRANSCODE", "PREPARING", 10, 88)));

        Assert.Equal(1, api.ResolveCalls);
        Assert.Equal(new long[] { 10 }, output.LoadedFileIds);
        Assert.Equal("http://server/api/stream/10", output.LoadedStreamUrls.Single());
        Assert.Equal(new[] { 1 }, output.AudioTrackIndices);
    }

    [Fact]
    public async Task Ready_variant_without_audio_metadata_uses_the_selected_source_layout()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 10);
        var descriptor = new PlaybackDescriptor(
            "TRANSCODE", "READY", 10, 88,
            "http://server/api/playback/stream/88", 0, null, null, null, null);
        var coordinator = new PlaybackCoordinator(new ResolvingServerApi(source, descriptor), output);

        await coordinator.ApplySnapshotAsync(
            "sync_full",
            Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK, playback: descriptor));

        Assert.Equal(new long[] { 88 }, output.LoadedFileIds);
        Assert.Equal(new[] { 1 }, output.AudioTrackIndices);
        Assert.Equal(new[] { ChannelMapMode.STEREO }, output.ChannelModes);
    }

    [Theory]
    [InlineData(null, "服务器未能生成可播放的 MV 变体。")]
    [InlineData("synthetic encoder failure", "synthetic encoder failure")]
    public async Task Failed_transcode_preserves_server_error_or_uses_a_safe_default(
        string? errorMessage,
        string expectedMessage)
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10);
        var descriptor = new PlaybackDescriptor(
            "TRANSCODE", "FAILED", 10, 88, ErrorMessage: errorMessage);
        var coordinator = new PlaybackCoordinator(new ResolvingServerApi(source, descriptor), output);

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", playback: descriptor)));

        Assert.Equal(PlaybackFailureKind.TranscodeFailure, exception.FailureKind);
        Assert.Equal(expectedMessage, exception.Message);
        Assert.Empty(output.LoadedFileIds);
        Assert.Equal(1, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Regular_transcode_ready_without_variant_identity_fails_closed()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10);
        var descriptor = new PlaybackDescriptor(
            "TRANSCODE", "READY", 10, null, "http://server/api/playback/stream/unknown");
        var coordinator = new PlaybackCoordinator(new ResolvingServerApi(source, descriptor), output);

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", playback: descriptor)));

        Assert.Equal(PlaybackFailureKind.TranscodeFailure, exception.FailureKind);
        Assert.Equal("MV 变体缺少播放标识。", exception.Message);
        Assert.Empty(output.LoadedFileIds);
        Assert.Equal(1, output.StopCount);
    }
    [Fact]
    public async Task Preparing_playback_waits_until_ready_beyond_two_minutes()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 10);
        var api = new SlowPreparingServerApi(source, preparingResponses: 120);
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.Zero);
        var preparing = new PlaybackDescriptor("TRANSCODE", "PREPARING", 10, 88);

        await coordinator.ApplySnapshotAsync(
            "sync_full", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK, playback: preparing));

        Assert.Equal(new long[] { 88 }, output.LoadedFileIds);
        Assert.Equal(121, api.ResolveCalls);
    }    [Fact]
    public async Task Preparing_playback_uses_a_configured_nonzero_poll_delay()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10);
        var api = new SlowPreparingServerApi(source, preparingResponses: 1);
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.FromMilliseconds(1));

        await coordinator.ApplySnapshotAsync(
            "sync_full",
            Snapshot("original", playback: new PlaybackDescriptor("TRANSCODE", "PREPARING", 10, 88)));

        Assert.Equal(2, api.ResolveCalls);
        Assert.Equal(new long[] { 88 }, output.LoadedFileIds);
    }

    [Fact]
    public void Negative_poll_delay_is_rejected()
    {
        Assert.Throws<ArgumentOutOfRangeException>(() =>
            new PlaybackCoordinator(new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)),
                new RecordingPlaybackOutput(), TimeSpan.FromTicks(-1)));
    }

    [Fact]
    public async Task Vocal_change_on_same_queue_does_not_reload_or_seek()
    {
        var output = new RecordingPlaybackOutput();
        var api = new FakeServerApi(FileSourceFor(AudioLayout.DUAL_CHANNEL));
        var coordinator = new PlaybackCoordinator(api, output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_CHANNEL));
        await coordinator.ApplySnapshotAsync("vocal_changed", Snapshot("original", audioLayout: AudioLayout.DUAL_CHANNEL));

        Assert.Equal(1, output.LoadCount);
        Assert.Equal(0, output.SeekCount);
        Assert.Equal(
            new[] { ChannelMapMode.RIGHT_MONO, ChannelMapMode.LEFT_MONO },
            output.ChannelModes);
    }

    [Fact]
    public async Task Dual_track_uses_server_audio_relative_track_index()
    {
        var output = new RecordingPlaybackOutput();
        var api = new FakeServerApi(FileSourceFor(AudioLayout.DUAL_TRACK));
        var coordinator = new PlaybackCoordinator(api, output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK));

        Assert.Equal(new[] { 1 }, output.AudioTrackIndices);
        Assert.Equal(1, output.LoadCount);
    }

    [Fact]
    public async Task Queue_change_loads_the_new_file_once()
    {
        var output = new RecordingPlaybackOutput();
        var api = new FakeServerApi(
            FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10),
            FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 11));
        var coordinator = new PlaybackCoordinator(api, output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", queueId: 1, songId: 100));
        await coordinator.ApplySnapshotAsync("now_playing", Snapshot("original", queueId: 2, songId: 101));

        Assert.Equal(2, output.LoadCount);
        Assert.Equal(new long[] { 10, 11 }, output.LoadedFileIds);
    }

    [Fact]
    public async Task Active_output_queue_changes_only_after_file_load_succeeds()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        Assert.Null(coordinator.ActiveOutputQueueId);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

        Assert.Equal(1L, coordinator.ActiveOutputQueueId);
    }

    [Fact]
    public async Task Active_queue_lookup_rejects_a_stale_output_generation()
    {
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), new RecordingPlaybackOutput());
        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        var active = Assert.IsType<ActiveOutputIdentity>(coordinator.ActiveOutput);

        Assert.True(coordinator.TryGetActiveQueueId(active, out var queueId));
        Assert.Equal(1L, queueId);
        Assert.False(coordinator.TryGetActiveQueueId(active with { Generation = active.Generation + 1 }, out _));
    }

    [Fact]
    public async Task Failed_file_load_does_not_publish_a_new_active_queue()
    {
        var output = new RecordingPlaybackOutput { ThrowOnLoad = true };
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Null(coordinator.ActiveOutputQueueId);
    }

    [Fact]
    public async Task Failed_replacement_load_clears_the_previous_active_output()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10),
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 11)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", queueId: 1, songId: 100));
        output.ThrowOnNextLoad = true;

        await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("now_playing", Snapshot("original", queueId: 2, songId: 101)));

        Assert.Null(coordinator.ActiveOutputQueueId);
    }

    [Fact]
    public async Task Reconnected_sync_for_same_queue_is_idempotent()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

        Assert.Equal(1, output.LoadCount);
        Assert.Equal(0, output.SeekCount);
    }

    [Fact]
    public async Task Seek_and_replay_seek_only_when_server_explicitly_requests_it()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync("playback_seeked", Snapshot("original", positionMs: 12_345, seekSequence: 1));
        await coordinator.ApplySnapshotAsync("playback_restarted", Snapshot("original", positionMs: 0, seekSequence: 2));

        Assert.Equal(new long[] { 12_345, 0 }, output.SeekPositions);
        Assert.Equal(1, output.LoadCount);
    }

    [Fact]
    public async Task A_new_seek_sequence_is_applied_independently_of_event_name()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync(
            "player_state", Snapshot("original", positionMs: 8_765, seekSequence: 1));

        Assert.Equal(new long[] { 8_765 }, output.SeekPositions);
    }

    [Fact]
    public async Task Idle_snapshot_stops_the_current_media()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync("player_state", Snapshot("original", state: "idle"));

        Assert.Equal(1, output.StopCount);
    }

    [Fact]
    public async Task Missing_playing_queue_or_song_identity_stops_the_current_projection()
    {
        var invalidSnapshots = new[]
        {
            Snapshot("original") with { Playing = null },
            Snapshot("original") with { Playing = new NowPlaying(null, new SongDto(100, "Song", "Artist"), null) },
            Snapshot("original") with { Playing = new NowPlaying(1, null, null) },
        };

        foreach (var invalidSnapshot in invalidSnapshots)
        {
            var output = new RecordingPlaybackOutput();
            var coordinator = new PlaybackCoordinator(
                new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);
            await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

            await coordinator.ApplySnapshotAsync("player_state", invalidSnapshot);

            Assert.Equal(1, output.StopCount);
            Assert.Null(coordinator.ActiveOutput);
        }
    }

    [Fact]
    public async Task Paused_snapshot_pauses_without_reloading_the_current_media()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync("player_state", Snapshot("original", state: "paused"));

        Assert.Equal(1, output.LoadCount);
        Assert.Equal(1, output.PauseCount);
        Assert.Equal(1, output.PlayCount);
    }

    [Fact]
    public async Task Unchanged_snapshot_does_not_repeat_volume_or_mute_commands()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

        Assert.Single(output.VolumeChanges);
    }

    [Fact]
    public async Task Explicit_negative_seek_is_normalized_to_zero()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.ApplySnapshotAsync(
            "playback_seeked", Snapshot("original", positionMs: -1, seekSequence: 1));

        Assert.Equal(new long[] { 0 }, output.SeekPositions);
    }

    [Fact]
    public async Task Missing_file_source_has_no_file_identity_for_play_error()
    {
        var coordinator = new PlaybackCoordinator(new MissingFileServerApi(), new RecordingPlaybackOutput());

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Equal(1, exception.QueueId);
        Assert.Null(exception.FileId);
        Assert.Equal(PlaybackFailureKind.MediaMissing, exception.FailureKind);
    }

    [Fact]
    public async Task Load_failure_reports_the_file_that_was_selected()
    {
        var output = new RecordingPlaybackOutput { ThrowOnLoad = true };
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10)), output);

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Equal(10, exception.FileId);
        Assert.Equal(PlaybackFailureKind.OutputFailure, exception.FailureKind);
        Assert.Equal(1, output.StopCount);
    }

    [Fact]
    public async Task Server_auth_failure_stops_local_projection_without_skipping_queue()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new ThrowingServerApi(new KtvApiException(401, "UNAUTHORIZED", "bad credential")), output);

        var exception = await Assert.ThrowsAsync<KtvApiException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Equal(401, exception.StatusCode);
        Assert.Equal(1, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Output_playback_failure_keeps_its_domain_identity_instead_of_being_wrapped()
    {
        var source = FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10);
        var domainFailure = new PlaybackAttemptException(
            1, 10, "synthetic output playback failure", PlaybackFailureKind.OutputFailure);
        var output = new DomainFailingPlaybackOutput(domainFailure);
        var coordinator = new PlaybackCoordinator(new FakeServerApi(source), output);

        var thrown = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Same(domainFailure, thrown);
        Assert.Equal(1, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Invalidate_uses_the_output_fence_when_the_renderer_provides_one()
    {
        var output = new FencedRecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);
        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

        await coordinator.InvalidateOutputProjectionAsync();

        Assert.Equal(1, output.FenceStopCount);
        Assert.Equal(0, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Superseded_work_is_rejected_even_when_its_token_is_not_cancelled()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await Assert.ThrowsAsync<OperationCanceledException>(() => coordinator.ApplySnapshotAsync(
            "sync_full", Snapshot("original"), CancellationToken.None, static () => false));

        Assert.Empty(output.LoadedFileIds);
        Assert.Equal(0, output.PlayCount);
    }

    [Fact]
    public async Task Superseded_output_failure_does_not_stop_the_newer_projection()
    {
        var isCurrent = true;
        var output = new SupersededFailingLoadOutput(() => isCurrent = false) { ThrowOnLoad = true };
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() => coordinator.ApplySnapshotAsync(
            "sync_full", Snapshot("original"), CancellationToken.None, () => isCurrent));

        Assert.Equal(PlaybackFailureKind.OutputFailure, exception.FailureKind);
        Assert.Equal(0, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Invalidate_output_projection_stops_an_already_loaded_output()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO)), output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));
        await coordinator.InvalidateOutputProjectionAsync();

        Assert.Equal(1, output.StopCount);
        Assert.Null(coordinator.ActiveOutput);
    }

    [Fact]
    public async Task Superseded_load_cannot_commit_or_start_the_old_song()
    {
        var output = new SupersededLoadOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10),
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 11)), output);

        await using var pump = new PlaybackSnapshotPump(
            (work, cancellationToken) => coordinator.ApplySnapshotAsync(
                work.EventType, work.Snapshot, cancellationToken, work.IsCurrent));

        pump.Submit("now_playing", Snapshot("original", queueId: 1, songId: 100));
        await output.FirstLoadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));

        pump.Submit("now_playing", Snapshot("original", queueId: 2, songId: 101));
        output.ReleaseFirstLoad.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new long[] { 10, 11 }, output.LoadedFileIds);
        Assert.Equal(new long[] { 11 }, output.PlayedFileIds);
        Assert.Equal(2L, coordinator.ActiveOutputQueueId);
    }

    [Fact]
    public async Task Superseded_load_uses_latest_pause_volume_vocal_and_position_state()
    {
        var output = new SupersededLoadOutput();
        var coordinator = new PlaybackCoordinator(
            new FakeServerApi(FileSourceFor(AudioLayout.DUAL_CHANNEL)), output);

        await using var pump = new PlaybackSnapshotPump(
            (work, cancellationToken) => coordinator.ApplySnapshotAsync(
                work.EventType, work.Snapshot, cancellationToken, work.IsCurrent));

        pump.Submit("now_playing", Snapshot("accompaniment", state: "playing", audioLayout: AudioLayout.DUAL_CHANNEL));
        await output.FirstLoadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));

        pump.Submit(
            "player_state",
            Snapshot(
                "original",
                state: "paused",
                volume: 22,
                muted: true,
                positionMs: 4_321,
                seekSequence: 1,
                audioLayout: AudioLayout.DUAL_CHANNEL));
        output.ReleaseFirstLoad.TrySetResult(true);
        await pump.WaitForIdleAsync().WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal(new long[] { 10, 10 }, output.LoadedFileIds);
        Assert.Empty(output.PlayedFileIds);
        Assert.Equal(1, output.PauseCount);
        Assert.Equal(new[] { (22, true) }, output.VolumeChanges);
        Assert.Equal(new long[] { 4_321 }, output.SeekPositions);
        Assert.Equal(new[] { ChannelMapMode.LEFT_MONO }, output.ChannelModes);
    }

    /**
     * The server may publish a higher-priority file row that has not finished
     * probing. Priority alone must not select a source that cannot be streamed.
     */
    [Fact]
    public async Task Not_ready_highest_priority_file_is_skipped_for_a_ready_lower_priority_file()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new MultiFileServerApi(
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 21, ready: false),
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 22, ready: true)),
            output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original"));

        Assert.Equal(new long[] { 22 }, output.LoadedFileIds);
    }

    [Fact]
    public async Task Song_with_only_not_ready_files_fails_before_requesting_a_stream()
    {
        var output = new RecordingPlaybackOutput();
        var api = new MultiFileServerApi(
            FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 31, ready: false));
        var coordinator = new PlaybackCoordinator(api, output);

        await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original")));

        Assert.Empty(output.LoadedFileIds);
        Assert.Empty(api.RequestedStreams);
    }

    [Fact]
    public async Task Idle_playback_descriptor_without_a_resolved_stream_fails_closed()
    {
        var output = new RecordingPlaybackOutput();
        var source = FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 10);
        var descriptor = new PlaybackDescriptor("TRANSCODE", "IDLE", 10, 88);
        var api = new ResolvingServerApi(source, descriptor);
        var coordinator = new PlaybackCoordinator(api, output, TimeSpan.Zero);

        var exception = await Assert.ThrowsAsync<PlaybackAttemptException>(() =>
            coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", playback: descriptor)));

        Assert.Equal(1, api.ResolveCalls);
        Assert.Equal(PlaybackFailureKind.SourceUnavailable, exception.FailureKind);
        Assert.Equal("服务器没有返回可播放的媒体地址。", exception.Message);
        Assert.Empty(output.LoadedFileIds);
    }

    /** An older server omits the ready flag; the client must keep working. */
    [Fact]
    public void File_source_without_a_ready_field_deserializes_as_unknown()
    {
        var source = JsonSerializer.Deserialize<FileSource>(
            """
            {"id":7,"format":"matroska","audioTracks":1,"vocalTrackIndex":null,
             "resolution":"1080p","priority":3,"audioLayout":{"layout":"NORMAL_STEREO"}}
            """,
            ProtocolJson.Options);

        Assert.NotNull(source);
        Assert.Equal(7L, source!.Id);
        Assert.Null(source.Ready);
    }

    [Fact]
    public async Task Vocal_changed_event_with_new_snapshot_layout_reapplies_audio()
    {
        var output = new RecordingPlaybackOutput();
        var api = new FakeServerApi(FileSourceFor(AudioLayout.NORMAL_STEREO));
        var coordinator = new PlaybackCoordinator(api, output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("original", audioLayout: AudioLayout.NORMAL_STEREO));
        Assert.Equal(new[] { ChannelMapMode.STEREO }, output.ChannelModes);

        await coordinator.ApplySnapshotAsync("vocal_changed", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_CHANNEL));

        Assert.Equal(
            new[] { ChannelMapMode.STEREO, ChannelMapMode.RIGHT_MONO },
            output.ChannelModes);
    }

    [Fact]
    public async Task Selected_ready_file_layout_overrides_a_stale_snapshot_layout()
    {
        var output = new RecordingPlaybackOutput();
        var coordinator = new PlaybackCoordinator(
            new MultiFileServerApi(
                FileSourceFor(AudioLayout.DUAL_TRACK, fileId: 21, ready: false),
                FileSourceFor(AudioLayout.NORMAL_STEREO, fileId: 22, ready: true)),
            output);

        await coordinator.ApplySnapshotAsync(
            "sync_full", Snapshot("accompaniment", audioLayout: AudioLayout.DUAL_TRACK));

        Assert.Equal(new[] { 0 }, output.AudioTrackIndices);
    }

    private static QueueSnapshot Snapshot(
        string vocalMode,
        long queueId = 1,
        long songId = 100,
        string state = "playing",
        int volume = 60,
        bool muted = false,
        long positionMs = 0,
        long seekSequence = 0,
        AudioLayout audioLayout = AudioLayout.NORMAL_STEREO, PlaybackDescriptor? playback = null) => new(
        new NowPlaying(queueId, new SongDto(songId, $"Song {songId}", "Artist"), null),
        Array.Empty<QueueEntry>(),
        state,
        volume,
        muted,
        vocalMode,
        new AudioLayoutDto(audioLayout,
            audioLayout == AudioLayout.DUAL_TRACK ? 0 : null,
            audioLayout == AudioLayout.DUAL_TRACK ? 1 : null,
            AudioChannel.LEFT, AudioChannel.RIGHT),
        true,
        0,
        positionMs,
        seekSequence,
        0,
        playback);

    private static FileSource FileSourceFor(AudioLayout layout, long fileId = 10, bool? ready = null) =>
        new(fileId, "matroska", layout == AudioLayout.DUAL_TRACK ? 2 : 1,
            layout == AudioLayout.DUAL_TRACK ? 1 : null,
            "1080p", 100, new AudioLayoutDto(layout,
                layout == AudioLayout.DUAL_TRACK ? 0 : null,
                layout == AudioLayout.DUAL_TRACK ? 1 : null,
                AudioChannel.LEFT, AudioChannel.RIGHT),
            ready);

    private sealed class SlowPreparingServerApi(
        FileSource source,
        int preparingResponses) : IPlaybackServerApi
    {
        public int ResolveCalls { get; private set; }

        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromResult<SongDetail?>(new SongDetail(
                songId, "Song", "Artist", "AUDIO", false, 100_000, "none", null, null,
                new[] { source }));

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";

        public Task<PlaybackDescriptor?> ResolvePlaybackAsync(
            long fileId, bool forceTranscode, CancellationToken cancellationToken = default)
        {
            ResolveCalls++;
            var descriptor = ResolveCalls <= preparingResponses
                ? new PlaybackDescriptor("TRANSCODE", "PREPARING", source.Id, 88)
                : new PlaybackDescriptor(
                    "TRANSCODE", "READY", source.Id, 88,
                    "http://server/api/playback/stream/88", 2, 1,
                    new AudioLayoutDto(AudioLayout.DUAL_TRACK, 0, 1), null, null);
            return Task.FromResult<PlaybackDescriptor?>(descriptor);
        }
    }
    private sealed class ResolvingServerApi(
        FileSource source,
        PlaybackDescriptor? readyDescriptor) : IPlaybackServerApi
    {
        public int ResolveCalls { get; private set; }

        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromResult<SongDetail?>(new SongDetail(
                songId, "Song", "Artist", "AUDIO", false, 100_000, "none", null, null,
                new[] { source }));

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";

        public Task<PlaybackDescriptor?> ResolvePlaybackAsync(
            long fileId, bool forceTranscode, CancellationToken cancellationToken = default)
        {
            ResolveCalls++;
            return Task.FromResult(readyDescriptor);
        }
    }
    private sealed class FakeServerApi(params FileSource[] files) : IPlaybackServerApi
    {
        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
        {
            var file = files.FirstOrDefault(f => f.Id == songId - 90) ?? files.FirstOrDefault();
            return Task.FromResult<SongDetail?>(new SongDetail(
                songId, "Song", "Artist", "AUDIO", false, 100_000, "none", null, null,
                new[] { file! }));
        }

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";
    }

    /** Returns every supplied file source so priority/readiness selection can be exercised. */
    private sealed class MultiFileServerApi(params FileSource[] files) : IPlaybackServerApi
    {
        public List<long> RequestedStreams { get; } = new();

        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromResult<SongDetail?>(new SongDetail(
                songId, "Song", "Artist", "AUDIO", false, 100_000, "none", null, null, files));

        public string StreamUrl(long fileId)
        {
            RequestedStreams.Add(fileId);
            return $"http://server/api/stream/{fileId}";
        }
    }

    private sealed class MissingFileServerApi : IPlaybackServerApi
    {
        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromResult<SongDetail?>(null);

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";
    }

    private sealed class ThrowingServerApi(Exception exception) : IPlaybackServerApi
    {
        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromException<SongDetail?>(exception);

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";
    }

    private class RecordingPlaybackOutput : IPlaybackOutput
    {
        public int LoadCount { get; private set; }
        public int SeekCount => SeekPositions.Count;
        public int StopCount { get; private set; }
        public int PlayCount { get; private set; }
        public int PauseCount { get; private set; }
        public bool ThrowOnLoad { get; init; }
        public bool ThrowOnNextLoad { get; set; }
        public List<long> LoadedFileIds { get; } = new();
        public List<string> LoadedStreamUrls { get; } = new();
        public List<long> SeekPositions { get; } = new();
        public List<int> AudioTrackIndices { get; } = new();
        public List<ChannelMapMode> ChannelModes { get; } = new();
        public List<(int Volume, bool Muted)> VolumeChanges { get; } = new();

        public virtual Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default)
        {
            if (ThrowOnLoad || ThrowOnNextLoad)
            {
                ThrowOnNextLoad = false;
                throw new InvalidOperationException("fake output load failed");
            }
            LoadCount++;
            LoadedFileIds.Add(fileId);
            LoadedStreamUrls.Add(streamUrl);
            return Task.CompletedTask;
        }

        public Task PlayAsync(CancellationToken cancellationToken = default)
        {
            PlayCount++;
            return Task.CompletedTask;
        }

        public Task PauseAsync(CancellationToken cancellationToken = default)
        {
            PauseCount++;
            return Task.CompletedTask;
        }

        public Task StopAsync(CancellationToken cancellationToken = default)
        {
            StopCount++;
            return Task.CompletedTask;
        }

        public Task SeekAsync(long positionMs, CancellationToken cancellationToken = default)
        {
            SeekPositions.Add(positionMs);
            return Task.CompletedTask;
        }

        public Task SetVolumeAsync(int volume, bool muted, CancellationToken cancellationToken = default)
        {
            VolumeChanges.Add((volume, muted));
            return Task.CompletedTask;
        }

        public Task SetAudioTrackAsync(int audioRelativeIndex, CancellationToken cancellationToken = default)
        {
            AudioTrackIndices.Add(audioRelativeIndex);
            return Task.CompletedTask;
        }

        public Task SetChannelModeAsync(ChannelMapMode mode, CancellationToken cancellationToken = default)
        {
            ChannelModes.Add(mode);
            return Task.CompletedTask;
        }
    }

    private sealed class FencedRecordingPlaybackOutput : RecordingPlaybackOutput, IPlaybackOutputFence
    {
        public int FenceStopCount { get; private set; }

        public Task StopIfRunningAsync(CancellationToken cancellationToken = default)
        {
            FenceStopCount++;
            return Task.CompletedTask;
        }
    }

    private sealed class SupersededFailingLoadOutput(Action markSuperseded) : RecordingPlaybackOutput
    {
        public override Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default)
        {
            markSuperseded();
            return base.LoadAsync(streamUrl, fileId, cancellationToken);
        }
    }

    private sealed class DomainFailingPlaybackOutput(PlaybackAttemptException failure) : RecordingPlaybackOutput
    {
        public override Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default) =>
            Task.FromException(failure);
    }

    private sealed class SupersededLoadOutput : IPlaybackOutput
    {
        public TaskCompletionSource<bool> FirstLoadStarted { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<bool> ReleaseFirstLoad { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        public List<long> LoadedFileIds { get; } = new();
        public List<string> LoadedStreamUrls { get; } = new();
        public List<long> PlayedFileIds { get; } = new();
        public int PauseCount { get; private set; }
        public List<(int Volume, bool Muted)> VolumeChanges { get; } = new();
        public List<long> SeekPositions { get; } = new();
        public List<ChannelMapMode> ChannelModes { get; } = new();

        public async Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default)
        {
            LoadedFileIds.Add(fileId);
            LoadedStreamUrls.Add(streamUrl);
            if (fileId == 10)
            {
                FirstLoadStarted.TrySetResult(true);
                await ReleaseFirstLoad.Task.WaitAsync(cancellationToken);
            }
        }

        public Task PlayAsync(CancellationToken cancellationToken = default)
        {
            PlayedFileIds.Add(LoadedFileIds[^1]);
            return Task.CompletedTask;
        }

        public Task PauseAsync(CancellationToken cancellationToken = default)
        {
            PauseCount++;
            return Task.CompletedTask;
        }
        public Task StopAsync(CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task SeekAsync(long positionMs, CancellationToken cancellationToken = default)
        {
            SeekPositions.Add(positionMs);
            return Task.CompletedTask;
        }
        public Task SetVolumeAsync(int volume, bool muted, CancellationToken cancellationToken = default)
        {
            VolumeChanges.Add((volume, muted));
            return Task.CompletedTask;
        }
        public Task SetAudioTrackAsync(int audioRelativeIndex, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task SetChannelModeAsync(ChannelMapMode mode, CancellationToken cancellationToken = default)
        {
            ChannelModes.Add(mode);
            return Task.CompletedTask;
        }
    }
}
