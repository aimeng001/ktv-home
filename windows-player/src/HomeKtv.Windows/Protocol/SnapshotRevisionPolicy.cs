namespace HomeKtv.Windows.Protocol;

/**
 * Accepts legacy revision 0 only until the first revisioned snapshot arrives;
 * afterwards an older or legacy response cannot roll the projection back.
 */
public static class SnapshotRevisionPolicy
{
    public static bool ShouldApply(long currentRevision, long incomingRevision) =>
        incomingRevision <= 0
            ? currentRevision <= 0
            : currentRevision <= 0 || incomingRevision >= currentRevision;
}
