namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class ProbeInfoTests
{
    [TestMethod]
    public void ReportsProbeName()
    {
        Assert.Contains("multi-output", ProbeInfo.Name);
    }

    [TestMethod]
    public void DocumentsCoordinatorOptionsWithoutATokenArgument()
    {
        Assert.Contains("--coordinator-url", ProbeInfo.Usage);
        Assert.Contains("--coordinator-trial", ProbeInfo.Usage);
        Assert.Contains("POCKETDISCO_COORDINATOR_TOKEN", ProbeInfo.Usage);
        Assert.DoesNotContain("--coordinator-token", ProbeInfo.Usage);
    }
}
