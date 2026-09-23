namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorTokenSourceTests
{
    [TestMethod]
    public void ReadsTheDedicatedEnvironmentVariable()
    {
        string? requestedVariable = null;

        var token = CoordinatorTokenSource.Read(variable =>
        {
            requestedVariable = variable;
            return "test-token";
        });

        Assert.AreEqual(CoordinatorTokenSource.EnvironmentVariableName, requestedVariable);
        Assert.AreEqual("test-token", token);
    }

    [TestMethod]
    [DataRow(null)]
    [DataRow("")]
    [DataRow("   ")]
    public void RejectsAMissingTokenWithoutIncludingTokenText(string? value)
    {
        var error = Assert.ThrowsExactly<InvalidOperationException>(() =>
            CoordinatorTokenSource.Read(_ => value));

        Assert.Contains(CoordinatorTokenSource.EnvironmentVariableName, error.Message);
        Assert.DoesNotContain("   ", error.Message);
    }
}
