using System.Buffers.Binary;
using System.Text;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class ToneGeneratorTests
{
    [TestMethod]
    public void CreatesMonoPcmWavAtFortyEightKilohertz()
    {
        var wav = ToneGenerator.CreateClickTrack(TimeSpan.FromSeconds(2));

        Assert.AreEqual("RIFF", Encoding.ASCII.GetString(wav, 0, 4));
        Assert.AreEqual("WAVE", Encoding.ASCII.GetString(wav, 8, 4));
        Assert.AreEqual(1, BinaryPrimitives.ReadInt16LittleEndian(wav.AsSpan(20, 2)));
        Assert.AreEqual(1, BinaryPrimitives.ReadInt16LittleEndian(wav.AsSpan(22, 2)));
        Assert.AreEqual(48_000, BinaryPrimitives.ReadInt32LittleEndian(wav.AsSpan(24, 4)));
        Assert.AreEqual(16, BinaryPrimitives.ReadInt16LittleEndian(wav.AsSpan(34, 2)));
        Assert.HasCount(44 + (2 * 48_000 * 2), wav);
        Assert.AreEqual(wav.Length - 8, BinaryPrimitives.ReadInt32LittleEndian(wav.AsSpan(4, 4)));
        Assert.AreEqual(wav.Length - 44, BinaryPrimitives.ReadInt32LittleEndian(wav.AsSpan(40, 4)));
    }

    [TestMethod]
    public void PlacesClicksAtOneSecondIntervals()
    {
        var wav = ToneGenerator.CreateClickTrack(TimeSpan.FromSeconds(2));

        Assert.AreNotEqual(0, ReadSample(wav, 10));
        Assert.AreEqual(0, ReadSample(wav, 4_000));
        Assert.AreNotEqual(0, ReadSample(wav, 48_010));
    }

    [TestMethod]
    public void RejectsNonPositiveDuration()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(
            () => ToneGenerator.CreateClickTrack(TimeSpan.Zero));
    }

    private static short ReadSample(byte[] wav, int sampleIndex) =>
        BinaryPrimitives.ReadInt16LittleEndian(wav.AsSpan(44 + (sampleIndex * 2), 2));
}
