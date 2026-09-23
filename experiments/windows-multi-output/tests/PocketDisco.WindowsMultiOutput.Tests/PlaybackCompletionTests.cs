namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class PlaybackCompletionTests
{
    [TestMethod]
    public async Task CompletesAfterEveryPlayerEnds()
    {
        var completion = new PlaybackCompletion(2);

        completion.MarkEnded(0);
        Assert.IsFalse(completion.Completion.IsCompleted);

        completion.MarkEnded(1);
        await completion.Completion;
    }

    [TestMethod]
    public void DuplicateEndedEventDoesNotCompleteOtherPlayer()
    {
        var completion = new PlaybackCompletion(2);

        completion.MarkEnded(0);
        completion.MarkEnded(0);

        Assert.IsFalse(completion.Completion.IsCompleted);
    }

    [TestMethod]
    public void RejectsInvalidPlayerIndex()
    {
        var completion = new PlaybackCompletion(2);

        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => completion.MarkEnded(2));
    }
}
