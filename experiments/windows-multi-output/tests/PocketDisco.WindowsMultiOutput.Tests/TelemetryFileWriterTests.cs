namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class TelemetryFileWriterTests
{
    [TestMethod]
    public async Task ExistingTelemetryFileIsNotOverwritten()
    {
        var directory = Path.Combine(Path.GetTempPath(), $"pocketdisco-test-{Guid.NewGuid():N}");
        var path = Path.Combine(directory, "run.ndjson");

        try
        {
            await TelemetryFileWriter.WriteNewAsync(path, "first", CancellationToken.None);

            await Assert.ThrowsExactlyAsync<IOException>(async () =>
                await TelemetryFileWriter.WriteNewAsync(path, "second", CancellationToken.None));
            Assert.AreEqual("first", await File.ReadAllTextAsync(path));
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
    }
}
