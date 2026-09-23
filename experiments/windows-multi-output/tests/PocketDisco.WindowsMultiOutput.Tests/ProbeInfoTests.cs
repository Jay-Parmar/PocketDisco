namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class ProbeInfoTests
{
    [TestMethod]
    public void ReportsProbeName()
    {
        Assert.Contains("multi-output", ProbeInfo.Name);
    }
}
