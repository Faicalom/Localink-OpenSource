using System.IO;
using System.Text.Json;
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
    private const string PayloadType = "localink_pairing";
    private const int PayloadVersion = 1;
    private static readonly Regex PairingTokenPattern = new(@"^\d{6}$", RegexOptions.Compiled);

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
            Platform: string.IsNullOrWhiteSpace(localDevice.Platform) ? AppConstants.DesktopPlatformName : localDevice.Platform,
            AppVersion: localDevice.AppVersion,
            SupportedModes: localDevice.SupportedModes,
            LocalIpAddresses: localDevice.LocalIpAddresses,
            ApiPort: localDevice.ApiPort,
            DiscoveryPort: localDevice.DiscoveryPort,
            GeneratedAtUtc: DateTimeOffset.UtcNow);

        var json = JsonSerializer.Serialize(payload, JsonDefaults.Options);

        using var generator = new QRCodeGenerator();
        using var qrData = generator.CreateQrCode(json, QRCodeGenerator.ECCLevel.Q);
        using var qrCode = new PngByteQRCode(qrData);
        var pngBytes = qrCode.GetGraphic(pixelsPerModule: 18, darkColorRgba: [18, 24, 34], lightColorRgba: [255, 255, 255], drawQuietZones: true);

        return LoadImageSource(pngBytes);
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
        string Type,
        int Version,
        string PairingToken,
        string DeviceId,
        string DeviceName,
        string Platform,
        string AppVersion,
        string[] SupportedModes,
        string[] LocalIpAddresses,
        int ApiPort,
        int DiscoveryPort,
        DateTimeOffset GeneratedAtUtc);
}
