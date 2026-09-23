namespace PocketDisco.WindowsMultiOutput;

public static class CoordinatorTokenSource
{
    public const string EnvironmentVariableName = "POCKETDISCO_COORDINATOR_TOKEN";

    public static string Read(Func<string, string?> readEnvironmentVariable)
    {
        ArgumentNullException.ThrowIfNull(readEnvironmentVariable);
        var token = readEnvironmentVariable(EnvironmentVariableName);
        if (string.IsNullOrWhiteSpace(token))
        {
            throw new InvalidOperationException(
                $"Set {EnvironmentVariableName} before using coordinator mode.");
        }

        return token;
    }
}
