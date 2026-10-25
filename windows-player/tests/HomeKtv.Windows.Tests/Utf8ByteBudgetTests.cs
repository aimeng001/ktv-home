using HomeKtv.Windows.ServerConnection;

namespace HomeKtv.Windows.Tests;

public sealed class Utf8ByteBudgetTests
{
    [Fact]
    public void EncodeBounded_checks_byte_count_before_allocating_the_encoded_buffer()
    {
        Assert.Equal(3, Utf8ByteBudget.GetByteCountAtMost("中", 3));
        Assert.Null(Utf8ByteBudget.GetByteCountAtMost("中", 2));
        Assert.NotNull(Utf8ByteBudget.EncodeBounded("😀", 4));
        Assert.Null(Utf8ByteBudget.EncodeBounded("😀", 3));
        Assert.Null(Utf8ByteBudget.EncodeBounded(
            new string('x', Utf8ByteBudget.MaxMessageBytes + 1),
            Utf8ByteBudget.MaxMessageBytes));
    }

    [Fact]
    public void TruncateToByteLimit_keeps_only_complete_utf8_code_points()
    {
        Assert.Equal("A中", Utf8ByteBudget.TruncateToByteLimit("A中😀B", 4));
        Assert.Equal("A中😀", Utf8ByteBudget.TruncateToByteLimit("A中😀B", 8));
        Assert.Equal("😀", Utf8ByteBudget.TruncateToByteLimit("😀B", 4));
    }

    [Fact]
    public void TruncateToByteLimit_returns_empty_for_null_empty_or_nonpositive_budget()
    {
        Assert.Equal(string.Empty, Utf8ByteBudget.TruncateToByteLimit(null, 10));
        Assert.Equal(string.Empty, Utf8ByteBudget.TruncateToByteLimit(string.Empty, 10));
        Assert.Equal(string.Empty, Utf8ByteBudget.TruncateToByteLimit("text", 0));
        Assert.Equal(string.Empty, Utf8ByteBudget.TruncateToByteLimit("text", -1));
    }

    [Fact]
    public void GetByteCountAtMost_handles_null_negative_and_exact_utf8_boundaries()
    {
        Assert.Null(Utf8ByteBudget.GetByteCountAtMost(null, 10));
        Assert.Null(Utf8ByteBudget.GetByteCountAtMost("a", -1));
        Assert.Equal(1, Utf8ByteBudget.GetByteCountAtMost("a", 1));
        Assert.Equal(2, Utf8ByteBudget.GetByteCountAtMost("é", 2));
        Assert.Equal(3, Utf8ByteBudget.GetByteCountAtMost("中", 3));
        Assert.Equal(4, Utf8ByteBudget.GetByteCountAtMost("😀", 4));
    }
}
