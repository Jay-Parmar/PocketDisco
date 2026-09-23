namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class ResourceCleanupTests
{
    [TestMethod]
    public void AttemptsEveryOperationAfterFailure()
    {
        var secondAttempted = false;
        var operations = new CleanupOperation[]
        {
            new("first", () => throw new InvalidOperationException("failed")),
            new("second", () => secondAttempted = true),
        };

        var warnings = ResourceCleanup.AttemptAll(operations);

        Assert.IsTrue(secondAttempted);
        Assert.HasCount(1, warnings);
        Assert.AreEqual("first", warnings[0]);
    }

    [TestMethod]
    public void ReturnsNoWarningsWhenCleanupSucceeds()
    {
        var warnings = ResourceCleanup.AttemptAll(
            [new CleanupOperation("only", () => { })]);

        Assert.IsEmpty(warnings);
    }
}
