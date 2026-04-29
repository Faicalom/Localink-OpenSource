using System.Text.Json;
using System.Text.Json.Serialization;

namespace LocalBridge.Core;

public static class JsonDefaults
{
    public static JsonSerializerOptions Options { get; } = CreateDefaultOptions();

    private static JsonSerializerOptions CreateDefaultOptions()
    {
        return new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DictionaryKeyPolicy = JsonNamingPolicy.CamelCase,
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
    }
}
