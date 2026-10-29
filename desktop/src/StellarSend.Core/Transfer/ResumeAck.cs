namespace StellarSend.Core.Transfer;

/// <summary>
/// Durable resume checkpoints. 1 MiB is a multiple of the 8 KB relay
/// WebSocket slice and stays under the 2 MB forward budget, so a drop
/// never restarts a large send from zero.
/// </summary>
public static class ResumeAck
{
    public const int IntervalBytes = 1024 * 1024;
    public const int WindowBytes = RelayBuffer.BudgetBytes;
    public const int RelaySliceBytes = RelayBuffer.SliceBytes;
}
