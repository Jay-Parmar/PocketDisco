using System.Text;

namespace PocketDisco.WindowsMultiOutput;

public static class TelemetryFileWriter
{
    public static async Task<string> WriteNewAsync(
        string path,
        string contents,
        CancellationToken cancellationToken)
    {
        var fullPath = Path.GetFullPath(path);
        var directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        var bytes = Encoding.UTF8.GetBytes(contents);
        await using var stream = new FileStream(
            fullPath,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.Read,
            4_096,
            FileOptions.Asynchronous);
        await stream.WriteAsync(bytes, cancellationToken);
        return fullPath;
    }
}
