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
}
