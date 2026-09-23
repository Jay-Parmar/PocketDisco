using System.Net;
using System.Net.Sockets;

namespace PocketDisco.WindowsMultiOutput;

public static class CoordinatorUrl
{
    public static string Normalize(string value)
    {
        if (string.IsNullOrWhiteSpace(value)
            || !Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Enter a valid coordinator URL.", nameof(value));
        }

        var trimmed = value.Trim();
        var scheme = uri.Scheme.ToLowerInvariant();
        if (scheme is not "http" and not "https")
        {
            throw new ArgumentException(
                "Coordinator URL must use HTTP or HTTPS.",
                nameof(value));
        }

        if (string.IsNullOrWhiteSpace(uri.Host))
        {
            throw new ArgumentException(
                "Coordinator URL must include a host.",
                nameof(value));
        }

        if (!string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Query)
            || !string.IsNullOrEmpty(uri.Fragment))
        {
            throw new ArgumentException(
                "Coordinator URL must not contain credentials, a query, or a fragment.",
                nameof(value));
        }

        if (uri.AbsolutePath != "/")
        {
            throw new ArgumentException(
                "Coordinator URL must not contain a path.",
                nameof(value));
        }

        var host = uri.IdnHost.ToLowerInvariant();
        if (scheme == "http" && !IsPrivateLanHost(GetRawHost(trimmed)))
        {
            throw new ArgumentException(
                "Plain HTTP is limited to a private LAN host.",
                nameof(value));
        }

        var builder = new UriBuilder(scheme, host, uri.IsDefaultPort ? -1 : uri.Port);
        return builder.Uri.GetLeftPart(UriPartial.Authority);
    }

    private static string GetRawHost(string value)
    {
        var authorityStart = value.IndexOf("://", StringComparison.Ordinal) + 3;
        var authorityEnd = value.IndexOfAny(['/', '?', '#'], authorityStart);
        var authority = authorityEnd < 0
            ? value[authorityStart..]
            : value[authorityStart..authorityEnd];

        if (authority.StartsWith('['))
        {
            var closingBracket = authority.IndexOf(']');
            return authority[1..closingBracket].ToLowerInvariant();
        }

        var portSeparator = authority.LastIndexOf(':');
        var host = portSeparator < 0 ? authority : authority[..portSeparator];
        return host.ToLowerInvariant();
    }

    private static bool IsPrivateLanHost(string host)
    {
        if (host == "localhost" || host.EndsWith(".local", StringComparison.Ordinal))
        {
            return true;
        }

        if (host.Contains(':'))
        {
            if (host.Any(character => character != ':' && !Uri.IsHexDigit(character))
                || !IPAddress.TryParse(host, out var ipv6)
                || ipv6.AddressFamily != AddressFamily.InterNetworkV6)
            {
                return false;
            }

            var bytes = ipv6.GetAddressBytes();
            return IPAddress.IsLoopback(ipv6)
                || ipv6.IsIPv6LinkLocal
                || bytes[0] is 0xfc or 0xfd;
        }

        var octets = host.Split('.');
        if (octets.Length != 4)
        {
            return false;
        }

        var values = new byte[4];
        for (var index = 0; index < octets.Length; index++)
        {
            var octet = octets[index];
            if (octet.Length == 0
                || (octet.Length > 1 && octet[0] == '0')
                || !byte.TryParse(octet, out values[index]))
            {
                return false;
            }
        }

        return values[0] == 10
            || values[0] == 127
            || (values[0] == 172 && values[1] is >= 16 and <= 31)
            || (values[0] == 192 && values[1] == 168);
    }
}
