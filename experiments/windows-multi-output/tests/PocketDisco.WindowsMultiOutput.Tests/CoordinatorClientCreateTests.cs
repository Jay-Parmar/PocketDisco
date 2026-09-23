using System.Net;
using System.Text;
using System.Text.Json;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorClientCreateTests
{
    private const string Token = "test-token";
    private const string IdempotencyKey = "windows-trial-1";
    private static readonly Guid TrialId = Guid.Parse("11111111-2222-3333-4444-555555555555");

    [TestMethod]
    public async Task CreatesGeneratedSignalTrialWithServerTimeLead()
    {
        var handler = new FakeHandler(async (request, cancellationToken) =>
        {
            Assert.AreEqual(HttpMethod.Post, request.Method);
            Assert.AreEqual("http://127.0.0.1:8765/v1/trials", request.RequestUri!.ToString());
            Assert.AreEqual(Token, request.Headers.Authorization!.Parameter);
            Assert.AreEqual(
                IdempotencyKey,
                request.Headers.GetValues("Idempotency-Key").Single());
            Assert.AreEqual("application/json", request.Content!.Headers.ContentType!.MediaType);

            var body = await request.Content.ReadAsStringAsync(cancellationToken);
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            Assert.AreEqual(4, root.EnumerateObject().Count());
            Assert.AreEqual(ToneGenerator.SignalId, root.GetProperty("asset_id").GetString());
            Assert.AreEqual(ToneGenerator.PcmSha256, root.GetProperty("asset_sha256").GetString());
            Assert.AreEqual(0, root.GetProperty("requested_position_ms").GetInt64());
            Assert.AreEqual(127_000, root.GetProperty("effective_at_unix_ms").GetInt64());
            return JsonResponse(HttpStatusCode.Created, ValidResponse(127_000));
        });
        using var client = Client(handler, () => 2_000);

        var trial = await client.CreateTrialAsync(Estimate(), IdempotencyKey);

        Assert.AreEqual(TrialId, trial.Id);
        Assert.AreEqual(127_000, trial.EffectiveAtUnixMilliseconds);
    }

    [TestMethod]
    public async Task ReusesTheExactPayloadForAnIdempotentReplay()
    {
        var bodies = new List<string>();
        var requestCount = 0;
        var handler = new FakeHandler(async (request, cancellationToken) =>
        {
            requestCount++;
            bodies.Add(await request.Content!.ReadAsStringAsync(cancellationToken));
            return JsonResponse(
                requestCount == 1 ? HttpStatusCode.Created : HttpStatusCode.OK,
                ValidResponse(127_000));
        });
        var timestamps = new Queue<long>([2_000, 4_000]);
        using var client = Client(handler, timestamps.Dequeue);

        var first = await client.CreateTrialAsync(Estimate(), IdempotencyKey);
        var replay = await client.CreateTrialAsync(Estimate(), IdempotencyKey);

        Assert.AreEqual(first, replay);
        Assert.HasCount(2, bodies);
        Assert.AreEqual(bodies[0], bodies[1]);
    }

    [TestMethod]
    [DataRow("")]
    [DataRow(".starts-with-punctuation")]
    [DataRow("contains space")]
    [DataRow("contains/slash")]
    [DataRow("contains,comma")]
    public async Task RejectsUnsafeIdempotencyKeys(string idempotencyKey)
    {
        var handler = new FakeHandler((_, _) =>
            Task.FromResult(JsonResponse(HttpStatusCode.Created, ValidResponse(127_000))));
        using var client = Client(handler, () => 2_000);

        await Assert.ThrowsExactlyAsync<ArgumentException>(async () =>
            await client.CreateTrialAsync(Estimate(), idempotencyKey));

        Assert.AreEqual(0, handler.RequestCount);
    }

    [TestMethod]
    public async Task RejectsOversizedIdempotencyKeys()
    {
        var handler = new FakeHandler((_, _) =>
            Task.FromResult(JsonResponse(HttpStatusCode.Created, ValidResponse(127_000))));
        using var client = Client(handler, () => 2_000);

        await Assert.ThrowsExactlyAsync<ArgumentException>(async () =>
            await client.CreateTrialAsync(Estimate(), new string('a', 129)));
    }

    [TestMethod]
    public async Task RejectsAResponseWithAnotherEffectiveTime()
    {
        var handler = new FakeHandler((_, _) =>
            Task.FromResult(JsonResponse(HttpStatusCode.Created, ValidResponse(128_000))));
        using var client = Client(handler, () => 2_000);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.CreateTrialAsync(Estimate(), IdempotencyKey));
    }

    [TestMethod]
    public async Task RejectsAnEstimateWithoutSevenSamples()
    {
        var handler = new FakeHandler((_, _) =>
            Task.FromResult(JsonResponse(HttpStatusCode.Created, ValidResponse(127_000))));
        using var client = Client(handler, () => 2_000);
        var estimate = Estimate() with { SampleCount = 6 };

        await Assert.ThrowsExactlyAsync<ArgumentException>(async () =>
            await client.CreateTrialAsync(estimate, IdempotencyKey));
    }

    private static CoordinatorClockEstimate Estimate() => new(
        ServerToStopwatchOffsetMilliseconds: 100_000,
        UncertaintyMilliseconds: 2,
        BestNetworkRoundTripTimeMilliseconds: 3,
        SampleCount: CoordinatorClockEstimator.RequiredSampleCount,
        StopwatchFrequency: 1_000);

    private static CoordinatorClient Client(FakeHandler handler, Func<long> timestamp) => new(
        "http://127.0.0.1:8765",
        Token,
        handler,
        timestamp);

    private static string ValidResponse(long effectiveAt) =>
        $$$"""
        {"trial":{"id":"{{{TrialId:D}}}","asset_id":"{{{ToneGenerator.SignalId}}}","asset_sha256":"{{{ToneGenerator.PcmSha256}}}","requested_position_ms":0,"effective_at_unix_ms":{{{effectiveAt}}},"created_at_unix_ms":102000}}
        """;

    private static HttpResponseMessage JsonResponse(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> response)
        : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            RequestCount++;
            return response(request, cancellationToken);
        }
    }
}
