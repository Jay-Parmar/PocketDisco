namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class EndpointSelectorTests
{
    private static readonly IReadOnlyList<RenderEndpoint> Endpoints =
    [
        new(0, "internal-a", "Speakers"),
        new(1, "internal-b", "Headphones"),
        new(2, "internal-c", "USB output"),
    ];

    [TestMethod]
    public void SelectsTwoDistinctEndpoints()
    {
        var result = EndpointSelector.Select(Endpoints, [0, 2]);

        Assert.IsTrue(result.IsValid);
        CollectionAssert.AreEqual(new[] { Endpoints[0], Endpoints[2] }, result.Endpoints.ToArray());
    }

    [TestMethod]
    public void RejectsDuplicateEndpointIndexes()
    {
        var result = EndpointSelector.Select(Endpoints, [1, 1]);

        Assert.AreEqual(EndpointSelectionFailure.Duplicate, result.Failure);
    }

    [TestMethod]
    public void RejectsMissingEndpointIndexes()
    {
        var result = EndpointSelector.Select(Endpoints, [0, 8]);

        Assert.AreEqual(EndpointSelectionFailure.NotFound, result.Failure);
    }

    [TestMethod]
    public void RequiresExactlyTwoEndpoints()
    {
        var result = EndpointSelector.Select(Endpoints, [0]);

        Assert.AreEqual(EndpointSelectionFailure.WrongCount, result.Failure);
    }
}
