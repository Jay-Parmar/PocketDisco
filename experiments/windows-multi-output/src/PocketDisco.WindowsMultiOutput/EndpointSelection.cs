namespace PocketDisco.WindowsMultiOutput;

public sealed record RenderEndpoint(int Index, string InternalId, string DisplayName);

public enum EndpointSelectionFailure
{
    None,
    WrongCount,
    Duplicate,
    NotFound,
}

public sealed record EndpointSelection(
    IReadOnlyList<RenderEndpoint> Endpoints,
    EndpointSelectionFailure Failure)
{
    public bool IsValid => Failure == EndpointSelectionFailure.None;
}

public static class EndpointSelector
{
    public static EndpointSelection Select(
        IReadOnlyList<RenderEndpoint> available,
        IReadOnlyList<int> requestedIndexes)
    {
        if (requestedIndexes.Count != 2)
        {
            return Failed(EndpointSelectionFailure.WrongCount);
        }

        if (requestedIndexes[0] == requestedIndexes[1])
        {
            return Failed(EndpointSelectionFailure.Duplicate);
        }

        var byIndex = available.ToDictionary(endpoint => endpoint.Index);
        if (!byIndex.TryGetValue(requestedIndexes[0], out var first)
            || !byIndex.TryGetValue(requestedIndexes[1], out var second))
        {
            return Failed(EndpointSelectionFailure.NotFound);
        }

        return new EndpointSelection([first, second], EndpointSelectionFailure.None);
    }

    private static EndpointSelection Failed(EndpointSelectionFailure failure) =>
        new([], failure);
}
