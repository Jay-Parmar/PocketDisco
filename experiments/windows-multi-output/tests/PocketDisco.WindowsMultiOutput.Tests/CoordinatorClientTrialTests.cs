using System.Net;
using System.Text;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorClientTrialTests
{
    private const string Token = "test-token";
    private static readonly Guid TrialId = Guid.Parse("11111111-2222-3333-4444-555555555555");

    [TestMethod]
    public async Task FetchesGeneratedSignalTrialByUuid()
    {
        var handler = new FakeHandler((request, _) =>
        {
            Assert.AreEqual(HttpMethod.Get, request.Method);
            Assert.AreEqual(
                $"http://127.0.0.1:8765/v1/trials/{TrialId:D}",
                request.RequestUri!.ToString());
            Assert.AreEqual(Token, request.Headers.Authorization!.Parameter);
            return JsonResponse(HttpStatusCode.OK, ValidResponse());
        });
        using var client = Client(handler);

        var trial = await client.FetchTrialAsync(TrialId);

        Assert.AreEqual(TrialId, trial.Id);
        Assert.AreEqual(ToneGenerator.SignalId, trial.AssetId);
        Assert.AreEqual(ToneGenerator.PcmSha256, trial.AssetSha256);
        Assert.AreEqual(0, trial.RequestedPositionMilliseconds);
        Assert.AreEqual(1_786_899_025_000, trial.EffectiveAtUnixMilliseconds);
        Assert.AreEqual(1_786_899_000_000, trial.CreatedAtUnixMilliseconds);
    }

    [TestMethod]
    [DataRow("{}")]
    [DataRow("{\"trial\":{},\"extra\":1}")]
    [DataRow("{\"trial\":null}")]
    [DataRow("{\"trial\":{\"id\":\"not-a-uuid\",\"asset_id\":\"generated-click-v1\",\"asset_sha256\":\"e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0\",\"requested_position_ms\":0,\"effective_at_unix_ms\":1786899025000,\"created_at_unix_ms\":1786899000000}}")]
    [DataRow("{\"trial\":{\"id\":\"11111111-2222-3333-4444-555555555555\",\"asset_id\":\"generated-click-v1\",\"asset_sha256\":\"e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0\",\"requested_position_ms\":0,\"effective_at_unix_ms\":1786899025000}}")]
    public async Task RejectsMalformedTrialResponses(string body)
    {
        var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, body));
        using var client = Client(handler);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.FetchTrialAsync(TrialId));
    }

    [TestMethod]
    [DataRow("wrong-signal", ToneGenerator.PcmSha256, 0)]
    [DataRow(ToneGenerator.SignalId, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 0)]
    [DataRow(ToneGenerator.SignalId, ToneGenerator.PcmSha256, 1)]
    public async Task RejectsTrialsForAnotherSignal(
        string assetId,
        string assetSha256,
        long requestedPositionMilliseconds)
    {
        var body = ValidResponse(
            assetId,
            assetSha256,
            requestedPositionMilliseconds);
        var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, body));
        using var client = Client(handler);

        var error = await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.FetchTrialAsync(TrialId));

        Assert.Contains("does not match", error.Message);
    }

    [TestMethod]
    public async Task RejectsTrialWhoseEffectiveTimeDoesNotFollowCreation()
    {
        var body = ValidResponse(effectiveAt: 1_786_899_000_000);
        var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, body));
        using var client = Client(handler);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.FetchTrialAsync(TrialId));
    }

    [TestMethod]
    public async Task RejectsAResponseForAnotherTrial()
    {
        var otherId = Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        var body = ValidResponse().Replace(
            TrialId.ToString("D"),
            otherId.ToString("D"),
            StringComparison.Ordinal);
        var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, body));
        using var client = Client(handler);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.FetchTrialAsync(TrialId));
    }

    [TestMethod]
    public async Task ReportsHttpErrorsWithoutReturningTheResponseBody()
    {
        var handler = new FakeHandler((_, _) => JsonResponse(
            HttpStatusCode.NotFound,
            "{\"error\":{\"code\":\"not_found\",\"message\":\"private-value\"}}"));
        using var client = Client(handler);

        var error = await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.FetchTrialAsync(TrialId));

        Assert.Contains("HTTP 404", error.Message);
        Assert.DoesNotContain("private-value", error.ToString());
    }

    private static CoordinatorClient Client(FakeHandler handler) => new(
        "http://127.0.0.1:8765",
        Token,
        handler,
        () => 1_000);

    private static string ValidResponse(
        string assetId = ToneGenerator.SignalId,
        string assetSha256 = ToneGenerator.PcmSha256,
        long requestedPositionMilliseconds = 0,
        long effectiveAt = 1_786_899_025_000) =>
        $$$"""
        {"trial":{"id":"{{{TrialId:D}}}","asset_id":"{{{assetId}}}","asset_sha256":"{{{assetSha256}}}","requested_position_ms":{{{requestedPositionMilliseconds}}},"effective_at_unix_ms":{{{effectiveAt}}},"created_at_unix_ms":1786899000000}}
        """;

    private static HttpResponseMessage JsonResponse(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler(
        Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> response)
        : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            Task.FromResult(response(request, cancellationToken));
    }
}
