namespace PocketDisco.WindowsMultiOutput;

public sealed record CleanupOperation(string Name, Action Action);

public static class ResourceCleanup
{
    public static IReadOnlyList<string> AttemptAll(
        IReadOnlyList<CleanupOperation> operations)
    {
        var warnings = new List<string>();
        foreach (var operation in operations)
        {
            try
            {
                operation.Action();
            }
            catch (Exception)
            {
                warnings.Add(operation.Name);
            }
        }

        return warnings;
    }
}
