using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace PocketDisco.WindowsMultiOutput;

public sealed class CoordinatorClient : IDisposable
{
    private const int MaximumBearerTokenLength = 128;
    private const int MaximumResponseBytes = 4_096;
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(3);
    private static readonly JsonDocumentOptions JsonOptions = new()
    {
        AllowTrailingCommas = false,
        CommentHandling = JsonCommentHandling.Disallow,
        MaxDepth = 8,
    };

    private readonly HttpClient httpClient;
    private readonly AuthenticationHeaderValue authorization;
    private readonly Func<long> stopwatchTimestamp;

    public CoordinatorClient(string baseUrl, string bearerToken)
        : this(
            baseUrl,
            bearerToken,
            new HttpClientHandler
            {
                AllowAutoRedirect = false,
                UseCookies = false,
            },
            Stopwatch.GetTimestamp)
    {
    }

    internal CoordinatorClient(
        string baseUrl,
        string bearerToken,
        HttpMessageHandler handler,
        Func<long> stopwatchTimestamp)
    {
        ArgumentNullException.ThrowIfNull(handler);
        ArgumentNullException.ThrowIfNull(stopwatchTimestamp);
        if (string.IsNullOrEmpty(bearerToken)
            || bearerToken.Length > MaximumBearerTokenLength
            || bearerToken.Any(character =>
                !char.IsAsciiLetterOrDigit(character) && character is not '_' and not '-'))
        {
            throw new ArgumentException("Coordinator bearer token is invalid.", nameof(bearerToken));
        }

        var normalizedUrl = CoordinatorUrl.Normalize(baseUrl);
        httpClient = new HttpClient(handler, disposeHandler: true)
        {
            BaseAddress = new Uri($"{normalizedUrl}/", UriKind.Absolute),
            MaxResponseContentBufferSize = MaximumResponseBytes,
            Timeout = Timeout.InfiniteTimeSpan,
        };
        authorization = new AuthenticationHeaderValue("Bearer", bearerToken);
        this.stopwatchTimestamp = stopwatchTimestamp;
    }

    public async Task<CoordinatorClockSample> SampleTimeAsync(
        CancellationToken cancellationToken = default)
    {
        var clientSendTimestamp = stopwatchTimestamp();
        var response = await SendJsonAsync(
            HttpMethod.Get,
            "v1/time",
            content: null,
            ParseTime,
            cancellationToken);
        var clientReceiveTimestamp = stopwatchTimestamp();
        try
        {
            return new CoordinatorClockSample(
                clientSendTimestamp,
                clientReceiveTimestamp,
                response.ServerReceiveUnixMilliseconds,
                response.ServerSendUnixMilliseconds);
        }
        catch (ArgumentOutOfRangeException)
        {
            throw new CoordinatorClientException("Coordinator returned invalid time data.");
        }
    }

    public Task<CoordinatorTrial> FetchTrialAsync(
        Guid trialId,
        CancellationToken cancellationToken = default) =>
        SendJsonAsync(
            HttpMethod.Get,
            $"v1/trials/{trialId:D}",
            content: null,
            root => ParseTrialResponse(root, trialId),
            cancellationToken);

    public void Dispose()
    {
        httpClient.Dispose();
    }

    private async Task<T> SendJsonAsync<T>(
        HttpMethod method,
        string path,
        HttpContent? content,
        Func<JsonElement, T> parse,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(method, path)
        {
            Content = content,
        };
        request.Headers.Authorization = authorization;
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(RequestTimeout);

        try
        {
            using var response = await httpClient.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                timeout.Token);
            if (!response.IsSuccessStatusCode)
            {
                throw new CoordinatorClientException(
                    $"Coordinator request failed with HTTP {(int)response.StatusCode}.");
            }

            if (!string.Equals(
                    response.Content.Headers.ContentType?.MediaType,
                    "application/json",
                    StringComparison.OrdinalIgnoreCase))
            {
                throw new CoordinatorClientException("Coordinator returned an invalid content type.");
            }

            var body = await ReadBoundedBodyAsync(response.Content, timeout.Token);
            using var document = JsonDocument.Parse(body, JsonOptions);
            return parse(document.RootElement);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new CoordinatorClientException("Coordinator request timed out.");
        }
        catch (HttpRequestException)
        {
            throw new CoordinatorClientException("Coordinator request failed.");
        }
        catch (JsonException)
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }
        catch (InvalidOperationException)
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }
    }

    private static async Task<ReadOnlyMemory<byte>> ReadBoundedBodyAsync(
        HttpContent content,
        CancellationToken cancellationToken)
    {
        if (content.Headers.ContentLength > MaximumResponseBytes)
        {
            throw new CoordinatorClientException("Coordinator response exceeded the size limit.");
        }

        await using var stream = await content.ReadAsStreamAsync(cancellationToken);
        using var buffer = new MemoryStream();
        var chunk = new byte[1_024];
        while (true)
        {
            var read = await stream.ReadAsync(chunk, cancellationToken);
            if (read == 0)
            {
                return buffer.ToArray();
            }

            if (buffer.Length + read > MaximumResponseBytes)
            {
                throw new CoordinatorClientException("Coordinator response exceeded the size limit.");
            }

            buffer.Write(chunk, 0, read);
        }
    }

    private static CoordinatorTimeResponse ParseTime(JsonElement root)
    {
        RequireExactProperties(
            root,
            "server_receive_unix_ms",
            "server_send_unix_ms");
        var serverReceive = ReadNonNegativeInt64(root, "server_receive_unix_ms");
        var serverSend = ReadNonNegativeInt64(root, "server_send_unix_ms");
        if (serverSend < serverReceive)
        {
            throw new CoordinatorClientException("Coordinator returned invalid time data.");
        }

        return new CoordinatorTimeResponse(serverReceive, serverSend);
    }

    private static CoordinatorTrial ParseTrialResponse(JsonElement root, Guid expectedTrialId)
    {
        RequireExactProperties(root, "trial");
        var trial = root.GetProperty("trial");
        RequireExactProperties(
            trial,
            "id",
            "asset_id",
            "asset_sha256",
            "requested_position_ms",
            "effective_at_unix_ms",
            "created_at_unix_ms");

        var idValue = ReadString(trial, "id");
        if (!Guid.TryParseExact(idValue, "D", out var id) || id != expectedTrialId)
        {
            throw new CoordinatorClientException("Coordinator returned invalid trial data.");
        }

        var assetId = ReadString(trial, "asset_id");
        var assetSha256 = ReadString(trial, "asset_sha256");
        var requestedPosition = ReadNonNegativeInt64(trial, "requested_position_ms");
        var effectiveAt = ReadNonNegativeInt64(trial, "effective_at_unix_ms");
        var createdAt = ReadNonNegativeInt64(trial, "created_at_unix_ms");
        if (assetId != ToneGenerator.SignalId
            || assetSha256 != ToneGenerator.PcmSha256
            || requestedPosition != 0
            || effectiveAt <= createdAt)
        {
            throw new CoordinatorClientException("Coordinator trial does not match the generated signal.");
        }

        return new CoordinatorTrial(
            id,
            assetId,
            assetSha256,
            requestedPosition,
            effectiveAt,
            createdAt);
    }

    private static void RequireExactProperties(JsonElement root, params string[] expected)
    {
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }

        var properties = root.EnumerateObject().Select(property => property.Name).ToArray();
        if (properties.Length != expected.Length
            || properties.Distinct(StringComparer.Ordinal).Count() != expected.Length
            || expected.Any(name => !properties.Contains(name, StringComparer.Ordinal)))
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }
    }

    private static long ReadNonNegativeInt64(JsonElement root, string propertyName)
    {
        var property = root.GetProperty(propertyName);
        if (property.ValueKind != JsonValueKind.Number
            || !property.TryGetInt64(out var value)
            || value < 0)
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }

        return value;
    }

    private static string ReadString(JsonElement root, string propertyName)
    {
        var property = root.GetProperty(propertyName);
        if (property.ValueKind != JsonValueKind.String
            || string.IsNullOrEmpty(property.GetString()))
        {
            throw new CoordinatorClientException("Coordinator returned invalid JSON.");
        }

        return property.GetString()!;
    }

    private sealed record CoordinatorTimeResponse(
        long ServerReceiveUnixMilliseconds,
        long ServerSendUnixMilliseconds);
}

public sealed record CoordinatorTrial(
    Guid Id,
    string AssetId,
    string AssetSha256,
    long RequestedPositionMilliseconds,
    long EffectiveAtUnixMilliseconds,
    long CreatedAtUnixMilliseconds);

public sealed class CoordinatorClientException(string message) : Exception(message);
