namespace PocketDisco.WindowsMultiOutput.Tests;

[TestClass]
public sealed class CoordinatorUrlTests
{
    [TestMethod]
    [DataRow("http://127.0.0.1:8765/", "http://127.0.0.1:8765")]
    [DataRow("http://localhost:8765", "http://localhost:8765")]
    [DataRow("http://disco.local:8765/", "http://disco.local:8765")]
    [DataRow("http://10.0.0.1:8765", "http://10.0.0.1:8765")]
    [DataRow("http://172.16.0.1:8765", "http://172.16.0.1:8765")]
    [DataRow("http://172.31.255.255:8765", "http://172.31.255.255:8765")]
    [DataRow("http://192.168.1.20:8765/", "http://192.168.1.20:8765")]
    [DataRow("http://[::1]:8765/", "http://[::1]:8765")]
    [DataRow("http://[fd00::1]:8765/", "http://[fd00::1]:8765")]
    [DataRow("http://[fe80::1]:8765/", "http://[fe80::1]:8765")]
    public void AcceptsPrivateLanHttpUrls(string value, string expected)
    {
        Assert.AreEqual(expected, CoordinatorUrl.Normalize(value));
    }

    [TestMethod]
    public void AcceptsPublicHttpsAndNormalizesTheHost()
    {
        var result = CoordinatorUrl.Normalize(" https://PUBLIC.example.test/ ");

        Assert.AreEqual("https://public.example.test", result);
    }

    [TestMethod]
    [DataRow("http://example.test")]
    [DataRow("http://172.15.255.255:8765")]
    [DataRow("http://172.32.0.1:8765")]
    [DataRow("http://192.167.1.1:8765")]
    [DataRow("http://[2001:4860:4860::8888]:8765")]
    [DataRow("http://127.1:8765")]
    [DataRow("http://2130706433:8765")]
    [DataRow("http://0x7f000001:8765")]
    [DataRow("http://0177.0.0.1:8765")]
    [DataRow("http://[::ffff:127.0.0.1]:8765")]
    public void RejectsPublicCleartextUrls(string value)
    {
        Assert.ThrowsExactly<ArgumentException>(() => CoordinatorUrl.Normalize(value));
    }

    [TestMethod]
    [DataRow("")]
    [DataRow("not-a-url")]
    [DataRow("ftp://192.168.1.20")]
    [DataRow("http://user:password@192.168.1.20:8765")]
    [DataRow("http://192.168.1.20:8765/v1")]
    [DataRow("http://192.168.1.20:8765?token=value")]
    [DataRow("http://192.168.1.20:8765#fragment")]
    public void RejectsMalformedOrUnsafeUrls(string value)
    {
        Assert.ThrowsExactly<ArgumentException>(() => CoordinatorUrl.Normalize(value));
    }
}
