using System.Net;
using System.Text;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorStartResolverTests
{
    private const string Token = "test-token";
    private static readonly Guid TrialId = Guid.Parse("11111111-2222-3333-4444-555555555555");
    private static readonly long[] RepeatedSampleTimestamps = [1_000, 1_010];

    [TestMethod]
    public async Task TakesSevenSamplesBeforeCreatingATrial()
    {
        var requests = new List<string>();
        var sampleIndex = 0;
        var handler = new FakeHandler(async (request, cancellationToken) =>
        {
            requests.Add(request.RequestUri!.AbsolutePath);
            if (request.Method == HttpMethod.Get)
            {
                var clientSend = 1_000 + (sampleIndex * 20);
                sampleIndex++;
                return JsonResponse(
                    HttpStatusCode.OK,
                    TimeResponse(clientSend + 100_000, clientSend + 100_010));
            }

            var requestBody = await request.Content!.ReadAsStringAsync(cancellationToken);
            StringAssert.Contains(requestBody, "\"effective_at_unix_ms\":126140");
            return JsonResponse(HttpStatusCode.Created, TrialResponse(126_140, 101_140));
        });
        using var client = Client(handler, SampleTimestamps(includeCreationTimestamp: true));
        var resolver = new CoordinatorStartResolver(
            client,
            new CoordinatorPlaybackOptions("http://127.0.0.1:8765", TrialId: null),
            () => 1_145,
            stopwatchFrequency: 1_000,
            () => "windows-trial-1");

        var resolution = await resolver.ResolveAsync();

        CollectionAssert.AreEqual(
            Enumerable.Repeat("/v1/time", 7).Append("/v1/trials").ToArray(),
            requests);
        Assert.AreEqual(26_140, resolution.Plan.DeadlineTimestamp);
        Assert.AreEqual(TimeSpan.Zero, resolution.Plan.InitialPosition);
        Assert.IsFalse(resolution.Plan.WasLate);
        Assert.AreEqual(TrialId, resolution.Context.TrialId);
        Assert.AreEqual(126_140, resolution.Context.EffectiveAtUnixMilliseconds);
        Assert.AreEqual(0, resolution.Context.ClockUncertaintyMilliseconds);
        Assert.AreEqual(26_140, resolution.Context.CommandTargetTimestamp);
    }

    [TestMethod]
    public async Task TakesSevenSamplesBeforeFetchingATrial()
    {
        var requestCount = 0;
        var handler = new FakeHandler((request, _) =>
        {
            requestCount++;
            if (requestCount <= 7)
            {
                var clientSend = 1_000 + ((requestCount - 1) * 20);
                return Task.FromResult(JsonResponse(
                    HttpStatusCode.OK,
                    TimeResponse(clientSend + 100_000, clientSend + 100_010)));
            }

            Assert.AreEqual($"/v1/trials/{TrialId:D}", request.RequestUri!.AbsolutePath);
            return Task.FromResult(JsonResponse(
                HttpStatusCode.OK,
                TrialResponse(106_500, 101_000)));
        });
        using var client = Client(handler, SampleTimestamps(includeCreationTimestamp: false));
        var resolver = new CoordinatorStartResolver(
            client,
            new CoordinatorPlaybackOptions("http://127.0.0.1:8765", TrialId),
            () => 1_500,
            stopwatchFrequency: 1_000,
            () => throw new AssertFailedException("Fetch mode must not create an idempotency key."));

        var resolution = await resolver.ResolveAsync();

        Assert.AreEqual(8, requestCount);
        Assert.AreEqual(6_500, resolution.Plan.DeadlineTimestamp);
        Assert.AreEqual(TrialId, resolution.Context.TrialId);
    }

    [TestMethod]
    public async Task AcceptsExactlyFiveSecondsOfRemainingLead()
    {
        using var client = FetchClient(106_500);
        var resolver = FetchResolver(client, () => 1_500);

        var resolution = await resolver.ResolveAsync();

        Assert.AreEqual(6_500, resolution.Plan.DeadlineTimestamp);
    }

    [TestMethod]
    public async Task RejectsLessThanFiveSecondsOfRemainingLead()
    {
        using var client = FetchClient(106_499);
        var resolver = FetchResolver(client, () => 1_500);

        var error = await Assert.ThrowsExactlyAsync<InvalidOperationException>(async () =>
            await resolver.ResolveAsync());

        Assert.Contains("lead", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [TestMethod]
    public async Task StopsSamplingWhenCancelled()
    {
        using var cancellation = new CancellationTokenSource();
        var requestCount = 0;
        var handler = new FakeHandler((_, cancellationToken) =>
        {
            requestCount++;
            if (requestCount == 2)
            {
                cancellation.Cancel();
            }

            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(JsonResponse(
                HttpStatusCode.OK,
                TimeResponse(101_000, 101_010)));
        });
        using var client = Client(handler, Enumerable.Repeat(1_000L, 14));
        var resolver = FetchResolver(client, () => 1_500);

        await Assert.ThrowsExactlyAsync<TaskCanceledException>(async () =>
            await resolver.ResolveAsync(cancellation.Token));

        Assert.IsLessThan(7, requestCount);
    }

    private static CoordinatorClient FetchClient(long effectiveAt)
    {
        var requestCount = 0;
        var handler = new FakeHandler((_, _) =>
        {
            requestCount++;
            var response = requestCount <= 7
                ? TimeResponse(101_000, 101_010)
                : TrialResponse(effectiveAt, 101_000);
            return Task.FromResult(JsonResponse(HttpStatusCode.OK, response));
        });
        return Client(
            handler,
            Enumerable.Range(0, 7).SelectMany(_ => RepeatedSampleTimestamps));
    }

    private static CoordinatorStartResolver FetchResolver(
        CoordinatorClient client,
        Func<long> timestamp) => new(
        client,
        new CoordinatorPlaybackOptions("http://127.0.0.1:8765", TrialId),
        timestamp,
        stopwatchFrequency: 1_000,
        () => throw new AssertFailedException("Fetch mode must not create an idempotency key."));

    private static CoordinatorClient Client(
        FakeHandler handler,
        IEnumerable<long> timestamps)
    {
        var values = new Queue<long>(timestamps);
        return new CoordinatorClient(
            "http://127.0.0.1:8765",
            Token,
            handler,
            values.Dequeue);
    }

    private static IEnumerable<long> SampleTimestamps(bool includeCreationTimestamp)
    {
        for (var index = 0; index < 7; index++)
        {
            yield return 1_000 + (index * 20);
            yield return 1_010 + (index * 20);
        }

        if (includeCreationTimestamp)
        {
            yield return 1_140;
        }
    }

    private static string TimeResponse(long receive, long send) =>
        $$"""{"server_receive_unix_ms":{{receive}},"server_send_unix_ms":{{send}}}""";

    private static string TrialResponse(long effectiveAt, long createdAt) =>
        $$$"""
        {"trial":{"id":"{{{TrialId:D}}}","asset_id":"{{{ToneGenerator.SignalId}}}","asset_sha256":"{{{ToneGenerator.PcmSha256}}}","requested_position_ms":0,"effective_at_unix_ms":{{{effectiveAt}}},"created_at_unix_ms":{{{createdAt}}}}}
        """;

    private static HttpResponseMessage JsonResponse(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> response)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            response(request, cancellationToken);
    }
}
