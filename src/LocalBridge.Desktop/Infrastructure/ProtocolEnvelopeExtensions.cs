using System.Net.Http;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Http;
using LocalBridge.Core;
using LocalBridge.Core.Protocol;

namespace LocalBridge.Desktop.Infrastructure;

public static class ProtocolEnvelopeExtensions
{
    public static ValueTask<ProtocolEnvelope<TPayload>?> ReadEnvelopeAsync<TPayload>(
        this HttpRequest request,
        CancellationToken cancellationToken = default)
    {
        return request.ReadFromJsonAsync<ProtocolEnvelope<TPayload>>(JsonDefaults.Options, cancellationToken);
    }

    public static Task<ProtocolEnvelope<TPayload>?> ReadEnvelopeAsync<TPayload>(
        this HttpContent content,
        CancellationToken cancellationToken = default)
    {
        return content.ReadFromJsonAsync<ProtocolEnvelope<TPayload>>(JsonDefaults.Options, cancellationToken);
    }

    public static Task WriteEnvelopeAsync<TPayload>(
        this HttpResponse response,
        ProtocolEnvelope<TPayload> envelope,
        CancellationToken cancellationToken = default)
    {
        return response.WriteAsJsonAsync(envelope, JsonDefaults.Options, cancellationToken);
    }
}
