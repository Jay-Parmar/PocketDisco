namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class EndpointListFormatterTests
{
    [TestMethod]
    public void FormatsIndexesAndDisplayNames()
    {
        RenderEndpoint[] endpoints =
        [
            new(0, "internal-a", "Speakers"),
            new(1, "internal-b", "Headphones"),
        ];

        var lines = EndpointListFormatter.Format(endpoints);

        Assert.HasCount(2, lines);
        Assert.AreEqual("[0] Speakers", lines[0]);
        Assert.AreEqual("[1] Headphones", lines[1]);
    }

    [TestMethod]
    public void ReportsWhenNoEndpointsAreActive()
    {
        var lines = EndpointListFormatter.Format([]);

        Assert.HasCount(1, lines);
        Assert.AreEqual("No active audio render endpoints found.", lines[0]);
    }
}
