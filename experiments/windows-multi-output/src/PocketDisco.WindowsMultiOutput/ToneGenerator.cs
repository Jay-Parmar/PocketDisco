using System.Buffers.Binary;
using System.Text;

namespace PocketDisco.WindowsMultiOutput;

public static class ToneGenerator
{
    public const int SampleRate = 48_000;

    private const int HeaderSize = 44;
    private const int BytesPerSample = 2;
    private const int ClickSamples = SampleRate / 50;
    private const double FrequencyHz = 1_000;
    private const double Amplitude = short.MaxValue * 0.12;

    public static byte[] CreateClickTrack(TimeSpan duration)
    {
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(duration, TimeSpan.Zero);

        var sampleCount = checked((int)Math.Round(duration.TotalSeconds * SampleRate));
        var dataSize = checked(sampleCount * BytesPerSample);
        var wav = new byte[checked(HeaderSize + dataSize)];
        WriteHeader(wav, dataSize);

        for (var sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++)
        {
            var clickSample = sampleIndex % SampleRate;
            if (clickSample >= ClickSamples)
            {
                continue;
            }

            var phase = 2 * Math.PI * FrequencyHz * clickSample / SampleRate;
            var envelope = 1d - ((double)clickSample / ClickSamples);
            var sample = (short)Math.Round(Math.Sin(phase) * Amplitude * envelope);
            BinaryPrimitives.WriteInt16LittleEndian(
                wav.AsSpan(HeaderSize + (sampleIndex * BytesPerSample), BytesPerSample),
                sample);
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
