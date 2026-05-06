using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Localink.Core;
using Localink.Desktop.Core;
using Localink.Desktop.Models;
using QRCoder;

namespace Localink.Desktop.Infrastructure;

public static class PairingQrCodeBuilder
{
    private const string PayloadType = "lkp";
    private const int PayloadVersion = 2;
    private static readonly Regex PairingTokenPattern = new(@"^\d{6}$", RegexOptions.Compiled);
    private static readonly JsonSerializerOptions QrJsonOptions = CreateQrJsonOptions();

    public static ImageSource? CreateImageSource(string pairingToken, LocalDeviceProfile localDevice)
    {
        var normalizedToken = pairingToken.Trim();
        if (!PairingTokenPattern.IsMatch(normalizedToken))
        {
            return null;
        }

        var payload = new PairingQrPayload(
            Type: PayloadType,
            Version: PayloadVersion,
            PairingToken: normalizedToken,
            DeviceId: localDevice.DeviceId,
            DeviceName: localDevice.DeviceName,
            LocalIpAddresses: NormalizeLocalIpAddresses(localDevice.LocalIpAddresses),
            ApiPort: localDevice.ApiPort);

        var json = JsonSerializer.Serialize(payload, QrJsonOptions);

        using var generator = new QRCodeGenerator();
        using var qrData = generator.CreateQrCode(json, QRCodeGenerator.ECCLevel.M);
        using var qrCode = new PngByteQRCode(qrData);
        var pngBytes = qrCode.GetGraphic(pixelsPerModule: 10, darkColorRgba: [18, 24, 34], lightColorRgba: [255, 255, 255], drawQuietZones: true);

        return LoadImageSource(pngBytes);
    }

    private static JsonSerializerOptions CreateQrJsonOptions()
    {
        return new JsonSerializerOptions(JsonDefaults.Options)
        {
            WriteIndented = false
        };
    }

    private static string[] NormalizeLocalIpAddresses(string[] addresses)
    {
        return addresses
            .Select(address => address.Trim())
            .Where(address => !string.IsNullOrWhiteSpace(address))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Take(2)
            .ToArray();
    }

    private static ImageSource LoadImageSource(byte[] pngBytes)
    {
        using var stream = new MemoryStream(pngBytes);
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private sealed record PairingQrPayload(
        [property: JsonPropertyName("t")] string Type,
        [property: JsonPropertyName("v")] int Version,
        [property: JsonPropertyName("p")] string PairingToken,
        [property: JsonPropertyName("id")] string DeviceId,
        [property: JsonPropertyName("n")] string DeviceName,
        [property: JsonPropertyName("ips")] string[] LocalIpAddresses,
        [property: JsonPropertyName("port")] int ApiPort);
}
