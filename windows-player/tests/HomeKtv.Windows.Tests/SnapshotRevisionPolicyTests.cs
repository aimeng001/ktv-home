using HomeKtv.Windows.Protocol;

namespace HomeKtv.Windows.Tests;

public sealed class SnapshotRevisionPolicyTests
{
    [Theory]
    [InlineData(0L, 0L, true)]
    [InlineData(0L, 10L, true)]
    [InlineData(10L, 10L, true)]
    [InlineData(10L, 11L, true)]
    [InlineData(11L, 10L, false)]
    [InlineData(11L, 0L, false)]
    public void Older_or_legacy_snapshot_cannot_overwrite_newer_revision(
        long currentRevision,
        long incomingRevision,
        bool expected)
    {
        Assert.Equal(expected,
            SnapshotRevisionPolicy.ShouldApply(currentRevision, incomingRevision));
    }
}
