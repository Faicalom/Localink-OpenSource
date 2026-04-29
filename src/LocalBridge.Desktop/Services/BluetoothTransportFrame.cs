using System.Buffers.Binary;
using System.IO;

namespace LocalBridge.Desktop.Services;

internal enum BluetoothFrameKind : byte
{
    JsonEnvelope = 1,
    JsonEnvelopeWithBinary = 2
}

internal sealed record BluetoothTransportFrame(
    BluetoothFrameKind Kind,
    string MetadataJson,
    byte[]? BinaryPayload = null);

internal static class BluetoothTransportFrameCodec
{
    public static async Task WriteJsonEnvelopeAsync(Stream stream, string metadataJson, CancellationToken cancellationToken)
    {
        var metadataBytes = System.Text.Encoding.UTF8.GetBytes(metadataJson);
        await WriteFrameAsync(
            stream,
            new BluetoothTransportFrame(BluetoothFrameKind.JsonEnvelope, metadataJson),
            metadataBytes,
            null,
            cancellationToken);
    }

    public static async Task WriteJsonEnvelopeWithBinaryAsync(
        Stream stream,
        string metadataJson,
        byte[] binaryPayload,
        CancellationToken cancellationToken)
    {
        var metadataBytes = System.Text.Encoding.UTF8.GetBytes(metadataJson);
        await WriteFrameAsync(
            stream,
            new BluetoothTransportFrame(BluetoothFrameKind.JsonEnvelopeWithBinary, metadataJson, binaryPayload),
            metadataBytes,
            binaryPayload,
            cancellationToken);
    }

    public static async Task<BluetoothTransportFrame?> ReadAsync(Stream stream, CancellationToken cancellationToken)
    {
        var header = new byte[5];
        var headerRead = await ReadExactlyAsync(stream, header, header.Length, cancellationToken);
        if (headerRead == 0)
        {
            return null;
        }

        if (headerRead != header.Length)
        {
            throw new IOException("Bluetooth frame header is incomplete.");
        }

        var frameKind = (BluetoothFrameKind)header[0];
        var metadataLength = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(1, 4));

        if (metadataLength <= 0 || metadataLength > Desktop.Core.AppConstants.BluetoothFrameMaxJsonBytes)
        {
            throw new IOException($"Bluetooth metadata frame size {metadataLength} is outside the accepted range.");
        }

        var metadataBytes = new byte[metadataLength];
        var metadataRead = await ReadExactlyAsync(stream, metadataBytes, metadataBytes.Length, cancellationToken);
        if (metadataRead != metadataBytes.Length)
        {
            throw new IOException("Bluetooth frame metadata is incomplete.");
        }

        byte[]? binaryPayload = null;
        if (frameKind == BluetoothFrameKind.JsonEnvelopeWithBinary)
        {
            var binaryLengthBuffer = new byte[4];
            var binaryLengthRead = await ReadExactlyAsync(stream, binaryLengthBuffer, binaryLengthBuffer.Length, cancellationToken);
            if (binaryLengthRead != binaryLengthBuffer.Length)
            {
                throw new IOException("Bluetooth binary frame length is incomplete.");
            }

            var binaryLength = BinaryPrimitives.ReadInt32BigEndian(binaryLengthBuffer);
            if (binaryLength < 0 || binaryLength > Desktop.Core.AppConstants.BluetoothTransferChunkSizeBytes)
            {
                throw new IOException($"Bluetooth binary frame size {binaryLength} is outside the accepted range.");
            }

            binaryPayload = new byte[binaryLength];
            var binaryRead = await ReadExactlyAsync(stream, binaryPayload, binaryPayload.Length, cancellationToken);
            if (binaryRead != binaryPayload.Length)
            {
                throw new IOException("Bluetooth binary frame payload is incomplete.");
            }
        }

        var metadataJson = System.Text.Encoding.UTF8.GetString(metadataBytes);
        return new BluetoothTransportFrame(frameKind, metadataJson, binaryPayload);
    }

    private static async Task WriteFrameAsync(
        Stream stream,
        BluetoothTransportFrame frame,
        byte[] metadataBytes,
        byte[]? binaryPayload,
        CancellationToken cancellationToken)
    {
        var header = new byte[5];
        header[0] = (byte)frame.Kind;
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(1, 4), metadataBytes.Length);

        await stream.WriteAsync(header, cancellationToken);
        await stream.WriteAsync(metadataBytes, cancellationToken);

        if (frame.Kind == BluetoothFrameKind.JsonEnvelopeWithBinary)
        {
            var payload = binaryPayload ?? [];
            var binaryLengthBuffer = new byte[4];
            BinaryPrimitives.WriteInt32BigEndian(binaryLengthBuffer, payload.Length);
            await stream.WriteAsync(binaryLengthBuffer, cancellationToken);
            if (payload.Length > 0)
            {
                await stream.WriteAsync(payload, cancellationToken);
            }
        }

        await stream.FlushAsync(cancellationToken);
    }

    private static async Task<int> ReadExactlyAsync(
        Stream stream,
        byte[] buffer,
        int expectedLength,
        CancellationToken cancellationToken)
    {
        var totalRead = 0;

        while (totalRead < expectedLength)
        {
            var bytesRead = await stream.ReadAsync(
                buffer.AsMemory(totalRead, expectedLength - totalRead),
                cancellationToken);

            if (bytesRead == 0)
            {
                return totalRead;
            }

            totalRead += bytesRead;
        }

        return totalRead;
    }
}
