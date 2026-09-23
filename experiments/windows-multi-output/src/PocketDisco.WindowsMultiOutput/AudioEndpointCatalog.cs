using Windows.Devices.Enumeration;
using Windows.Media.Devices;

namespace PocketDisco.WindowsMultiOutput;

public static class AudioEndpointCatalog
{
    public static async Task<IReadOnlyList<RenderEndpoint>> GetActiveAsync()
    {
        var selector = MediaDevice.GetAudioRenderSelector();
        var devices = await DeviceInformation.FindAllAsync(selector);

        return devices
            .Select((device, index) => new RenderEndpoint(index, device.Id, device.Name))
            .ToArray();
    }
}

public static class EndpointListFormatter
{
    public static IReadOnlyList<string> Format(IReadOnlyList<RenderEndpoint> endpoints)
    {
        if (endpoints.Count == 0)
        {
            return ["No active audio render endpoints found."];
        }

        return endpoints
            .Select(endpoint => $"[{endpoint.Index}] {endpoint.DisplayName}")
            .ToArray();
    }
}
