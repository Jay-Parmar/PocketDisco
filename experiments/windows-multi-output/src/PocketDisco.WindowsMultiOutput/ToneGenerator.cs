using System.Buffers.Binary;
using System.Text;

namespace PocketDisco.WindowsMultiOutput;

public static class ToneGenerator
{
    public const string SignalId = "generated-click-v1";
    public const string PcmSha256 = "e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0";
    public const int SampleRate = 48_000;

    private const int HeaderSize = 44;
    private const int BytesPerSample = 2;
    private const int ClickSamples = SampleRate / 50;
    private const int HalfWaveSamples = SampleRate / 2_000;
    private const int PeakAmplitude = 8_192;

    public static byte[] CreateCanonicalPcm()
    {
        var pcm = new byte[SampleRate * BytesPerSample];
        for (var sampleIndex = 0; sampleIndex < ClickSamples; sampleIndex++)
        {
            var level = PeakAmplitude * (ClickSamples - sampleIndex) / ClickSamples;
            var polarity = (sampleIndex / HalfWaveSamples) % 2 == 0 ? 1 : -1;
            BinaryPrimitives.WriteInt16LittleEndian(
                pcm.AsSpan(sampleIndex * BytesPerSample, BytesPerSample),
                (short)(level * polarity));
        }

        return pcm;
    }

    public static byte[] CreateClickTrack(TimeSpan duration)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(duration, TimeSpan.Zero);

        var sampleCount = checked((int)Math.Round(duration.TotalSeconds * SampleRate));
        var dataSize = checked(sampleCount * BytesPerSample);
        var wav = new byte[checked(HeaderSize + dataSize)];
        WriteHeader(wav, dataSize);

        var frame = CreateCanonicalPcm();
        var written = 0;
        while (written < dataSize)
        {
            var count = Math.Min(frame.Length, dataSize - written);
            frame.AsSpan(0, count).CopyTo(wav.AsSpan(HeaderSize + written, count));
            written += count;
        }

        return wav;
    }

    private static void WriteHeader(Span<byte> wav, int dataSize)
    {
        Encoding.ASCII.GetBytes("RIFF", wav[..4]);
        BinaryPrimitives.WriteInt32LittleEndian(wav.Slice(4, 4), dataSize + 36);
        Encoding.ASCII.GetBytes("WAVEfmt ", wav.Slice(8, 8));
        BinaryPrimitives.WriteInt32LittleEndian(wav.Slice(16, 4), 16);
        BinaryPrimitives.WriteInt16LittleEndian(wav.Slice(20, 2), 1);
        BinaryPrimitives.WriteInt16LittleEndian(wav.Slice(22, 2), 1);
        BinaryPrimitives.WriteInt32LittleEndian(wav.Slice(24, 4), SampleRate);
        BinaryPrimitives.WriteInt32LittleEndian(wav.Slice(28, 4), SampleRate * BytesPerSample);
        BinaryPrimitives.WriteInt16LittleEndian(wav.Slice(32, 2), BytesPerSample);
        BinaryPrimitives.WriteInt16LittleEndian(wav.Slice(34, 2), 16);
        Encoding.ASCII.GetBytes("data", wav.Slice(36, 4));
        BinaryPrimitives.WriteInt32LittleEndian(wav.Slice(40, 4), dataSize);
    }
}
