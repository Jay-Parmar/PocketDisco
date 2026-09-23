namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackStartGateTests
{
    [TestMethod]
    public async Task RejectsStartAfterPlayerFailure()
    {
        var gate = new PlaybackStartGate();
        var commandIssued = false;

        gate.MarkFailed(1, "device removed");

        var error = Assert.ThrowsExactly<InvalidOperationException>(
            () => gate.IssueStart(() => commandIssued = true));
        Assert.IsFalse(commandIssued);
        Assert.Contains("output-2", error.Message);
        await Assert.ThrowsExactlyAsync<InvalidOperationException>(
            async () => await gate.Failure);
    }

    [TestMethod]
    public async Task ReportsFailureAfterStartCommand()
    {
        var gate = new PlaybackStartGate();
        var commandIssued = false;

        gate.IssueStart(() => commandIssued = true);
        gate.MarkFailed(0, "render stopped");

        Assert.IsTrue(commandIssued);
        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(
            async () => await gate.Failure);
        Assert.Contains("render stopped", error.Message);
    }

    [TestMethod]
    public async Task RejectsStartAfterControllerFailure()
    {
        var gate = new PlaybackStartGate();
        var commandIssued = false;

        gate.MarkControllerFailed("clock stopped");

        var error = Assert.ThrowsExactly<InvalidOperationException>(
            () => gate.IssueStart(() => commandIssued = true));
        Assert.IsFalse(commandIssued);
        Assert.Contains("controller", error.Message);
        await Assert.ThrowsExactlyAsync<InvalidOperationException>(
            async () => await gate.Failure);
    }
}
