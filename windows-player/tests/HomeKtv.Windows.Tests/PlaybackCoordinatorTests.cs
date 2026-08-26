using HomeKtv.Windows.Playback;
using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class PlaybackCoordinatorTests
{
    [Fact]
    public async Task Vocal_change_on_same_queue_does_not_reload_or_seek()
    {
        var output = new RecordingPlaybackOutput();
        var api = new FakeServerApi(FileSourceFor(AudioLayout.DUAL_CHANNEL));
        var coordinator = new PlaybackCoordinator(api, output);

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("accompaniment"));
        await coordinator.ApplySnapshotAsync("vocal_changed", Snapshot("original"));

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

        await coordinator.ApplySnapshotAsync("sync_full", Snapshot("accompaniment"));

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
    }

    private static QueueSnapshot Snapshot(
        string vocalMode,
        long queueId = 1,
        long songId = 100,
        string state = "playing",
        long positionMs = 0,
        long seekSequence = 0) => new(
        new NowPlaying(queueId, new SongDto(songId, $"Song {songId}", "Artist"), null),
        Array.Empty<QueueEntry>(),
        state,
        60,
        false,
        vocalMode,
        new AudioLayoutDto(AudioLayout.NORMAL_STEREO, null, null, AudioChannel.LEFT, AudioChannel.RIGHT),
        true,
        0,
        positionMs,
        seekSequence);

    private static FileSource FileSourceFor(AudioLayout layout, long fileId = 10) =>
        new(fileId, "matroska", layout == AudioLayout.DUAL_TRACK ? 2 : 1,
            layout == AudioLayout.DUAL_TRACK ? 1 : null,
            "1080p", 100, new AudioLayoutDto(layout,
                layout == AudioLayout.DUAL_TRACK ? 0 : null,
                layout == AudioLayout.DUAL_TRACK ? 1 : null,
                AudioChannel.LEFT, AudioChannel.RIGHT));

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

    private sealed class MissingFileServerApi : IPlaybackServerApi
    {
        public Task<SongDetail?> GetSongDetailAsync(long songId, CancellationToken cancellationToken = default)
            => Task.FromResult<SongDetail?>(null);

        public string StreamUrl(long fileId) => $"http://server/api/stream/{fileId}";
    }

    private sealed class RecordingPlaybackOutput : IPlaybackOutput
    {
        public int LoadCount { get; private set; }
        public int SeekCount => SeekPositions.Count;
        public int StopCount { get; private set; }
        public int PlayCount { get; private set; }
        public int PauseCount { get; private set; }
        public bool ThrowOnLoad { get; init; }
        public List<long> LoadedFileIds { get; } = new();
        public List<long> SeekPositions { get; } = new();
        public List<int> AudioTrackIndices { get; } = new();
        public List<ChannelMapMode> ChannelModes { get; } = new();
        public List<(int Volume, bool Muted)> VolumeChanges { get; } = new();

        public Task LoadAsync(string streamUrl, long fileId, CancellationToken cancellationToken = default)
        {
            if (ThrowOnLoad) throw new InvalidOperationException("fake output load failed");
            LoadCount++;
            LoadedFileIds.Add(fileId);
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
}
