using System.Net;
using System.Text;

namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorClientTimeTests
{
    private const string Token = "test-token";

    [TestMethod]
    public async Task SendsAuthenticatedTimeRequestAndBracketsItWithStopwatchTimestamps()
    {
        var timestamps = new Queue<long>([1_000, 1_025]);
        var handler = new FakeHandler((request, _) =>
        {
            Assert.AreEqual(HttpMethod.Get, request.Method);
            Assert.AreEqual("http://127.0.0.1:8765/v1/time", request.RequestUri!.ToString());
            Assert.AreEqual("Bearer", request.Headers.Authorization!.Scheme);
            Assert.AreEqual(Token, request.Headers.Authorization.Parameter);
            Assert.AreEqual("application/json", request.Headers.Accept.Single().MediaType);
            return JsonResponse(
                HttpStatusCode.OK,
                "{\"server_receive_unix_ms\":10000,\"server_send_unix_ms\":10003}");
        });
        using var client = new CoordinatorClient(
            "http://127.0.0.1:8765",
            Token,
            handler,
            timestamps.Dequeue);

        var sample = await client.SampleTimeAsync();

        Assert.AreEqual(1_000, sample.ClientSendTimestamp);
        Assert.AreEqual(1_025, sample.ClientReceiveTimestamp);
        Assert.AreEqual(10_000, sample.ServerReceiveUnixMilliseconds);
        Assert.AreEqual(10_003, sample.ServerSendUnixMilliseconds);
        Assert.AreEqual(1, handler.RequestCount);
    }

    [TestMethod]
    [DataRow("{\"server_receive_unix_ms\":10000}")]
    [DataRow("{\"server_receive_unix_ms\":10000,\"server_send_unix_ms\":10003,\"extra\":1}")]
    [DataRow("{\"server_receive_unix_ms\":10000,\"server_receive_unix_ms\":10001,\"server_send_unix_ms\":10003}")]
    [DataRow("{\"server_receive_unix_ms\":\"10000\",\"server_send_unix_ms\":10003}")]
    [DataRow("{\"server_receive_unix_ms\":10003,\"server_send_unix_ms\":10000}")]
    [DataRow("{\"server_receive_unix_ms\":10000,\"server_send_unix_ms\":10003,}")]
    public async Task RejectsMalformedTimeResponses(string body)
    {
        var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, body));
        using var client = Client(handler);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.SampleTimeAsync());
    }

    [TestMethod]
    public async Task RejectsOversizedResponsesWithoutReadingThemAsJson()
    {
        var handler = new FakeHandler((_, _) => JsonResponse(
            HttpStatusCode.OK,
            new string('x', 4_097)));
        using var client = Client(handler);

        var error = await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.SampleTimeAsync());

        Assert.Contains("size limit", error.Message);
    }

    [TestMethod]
    public async Task DoesNotExposeTheBearerTokenInHttpErrors()
    {
        var handler = new FakeHandler((_, _) => JsonResponse(
            HttpStatusCode.Unauthorized,
            $"{{\"error\":\"{Token}\"}}"));
        using var client = Client(handler);

        var error = await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.SampleTimeAsync());

        Assert.DoesNotContain(Token, error.ToString());
    }

    [TestMethod]
    public async Task DoesNotFollowRedirectResponses()
    {
        var handler = new FakeHandler((_, _) => new HttpResponseMessage(HttpStatusCode.Redirect)
        {
            Headers = { Location = new Uri("https://example.test/collect") },
        });
        using var client = Client(handler);

        await Assert.ThrowsExactlyAsync<CoordinatorClientException>(async () =>
            await client.SampleTimeAsync());

        Assert.AreEqual(1, handler.RequestCount);
    }

    [TestMethod]
    public async Task PreservesCallerCancellation()
    {
        var handler = new FakeHandler((_, cancellationToken) =>
        {
            cancellationToken.ThrowIfCancellationRequested();
            return JsonResponse(HttpStatusCode.OK, "{}");
        });
        using var client = Client(handler);
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();

        await Assert.ThrowsExactlyAsync<TaskCanceledException>(async () =>
            await client.SampleTimeAsync(cancellation.Token));
    }

    [TestMethod]
    [DataRow("secret\r\nvalue")]
    [DataRow("secret,value")]
    [DataRow("secret\"value")]
    public void RejectsUnsafeBearerTokensWithoutEchoingThem(string token)
    {
        using var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, "{}"));

        var error = Assert.ThrowsExactly<ArgumentException>(() =>
            new CoordinatorClient(
                "http://127.0.0.1:8765",
                token,
                handler,
                () => 0));

        Assert.DoesNotContain("secret", error.ToString());
    }

    [TestMethod]
    public void RejectsOversizedBearerTokens()
    {
        using var handler = new FakeHandler((_, _) => JsonResponse(HttpStatusCode.OK, "{}"));

        Assert.ThrowsExactly<ArgumentException>(() =>
            new CoordinatorClient(
                "http://127.0.0.1:8765",
                new string('a', 129),
                handler,
                () => 0));
    }

    private static CoordinatorClient Client(FakeHandler handler) => new(
        "http://127.0.0.1:8765",
        Token,
        handler,
        new Queue<long>([1_000, 1_010]).Dequeue);

    private static HttpResponseMessage JsonResponse(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler(
        Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> response)
        : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            RequestCount++;
            return Task.FromResult(response(request, cancellationToken));
        }
    }
}
