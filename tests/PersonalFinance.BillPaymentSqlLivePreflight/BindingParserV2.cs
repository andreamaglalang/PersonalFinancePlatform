using System.Collections.Immutable;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal sealed class ParsedBinding<T> where T : class
{
    private readonly ImmutableArray<byte> snapshot;
    internal T Value { get; }
    internal BindingByteIdentity Identity { get; }
    private ParsedBinding(T value, byte[] bytes)
    {
        Value = value;
        snapshot = ImmutableArray.CreateRange(bytes);
        Identity = new(snapshot.Length, Convert.ToHexString(SHA256.HashData(bytes)));
    }
    internal static ParsedBinding<T> Parse(ReadOnlyMemory<byte> input)
    {
        if (input.Length is <= 0 or > BindingParserV2.MaximumBytes)
            throw new BindingException(BindingFailure.MalformedInput);
        if (typeof(T) != typeof(ReviewScopeV2) && typeof(T) != typeof(InvocationDescriptorV1)
            && typeof(T) != typeof(ObservationEnvelopeV1))
            throw new BindingException(BindingFailure.MalformedInput);
        // Parsing, validation and hashing share this private snapshot, never caller memory.
        var bytes = input.ToArray();
        try
        {
            using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 12 });
            if (document.RootElement.ValueKind != JsonValueKind.Object)
                throw new BindingException(BindingFailure.MalformedInput);
            BindingParserV2.Inspect(document.RootElement);
            var value = JsonSerializer.Deserialize<T>(bytes.AsSpan(), BindingParserV2.Options)
                ?? throw new BindingException(BindingFailure.MalformedInput);
            if (value is ReviewScopeV2 scope) BindingPolicyV2.Scope(scope);
            else if (value is InvocationDescriptorV1 descriptor) InvocationBindingPolicy.Descriptor(descriptor);
            else if (value is ObservationEnvelopeV1 envelope) BindingPolicyV2.EnvelopeShape(envelope);
            else throw new BindingException(BindingFailure.MalformedInput);
            return new(value, bytes);
        }
        catch (JsonException) { throw new BindingException(BindingFailure.MalformedInput); }
        catch (NotSupportedException) { throw new BindingException(BindingFailure.MalformedInput); }
    }
}

internal static class BindingParserV2
{
    internal const int MaximumBytes = 65_536;
    internal static ParsedBinding<ReviewScopeV2> Scope(ReadOnlyMemory<byte> bytes) => ParsedBinding<ReviewScopeV2>.Parse(bytes);
    internal static ParsedBinding<InvocationDescriptorV1> Descriptor(ReadOnlyMemory<byte> bytes) => ParsedBinding<InvocationDescriptorV1>.Parse(bytes);
    internal static ParsedBinding<ObservationEnvelopeV1> Envelope(ReadOnlyMemory<byte> bytes) => ParsedBinding<ObservationEnvelopeV1>.Parse(bytes);
    // A fresh options object prevents callers from mutating parser configuration.
    internal static JsonSerializerOptions Options => new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = false,
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
        RespectRequiredConstructorParameters = true, RespectNullableAnnotations = true,
        NumberHandling = JsonNumberHandling.Strict, MaxDepth = 12,
        Converters = { new ExactEnum<BindingOperation>(), new ExactEnum<BindingEvidenceSource>(),
            new ExactEnum<BindingObservationFailure>() }
    };
    internal static void Inspect(JsonElement element)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                var names = new HashSet<string>(StringComparer.Ordinal);
                foreach (var property in element.EnumerateObject())
                {
                    if (property.Name.Length > 64 || !names.Add(property.Name))
                        throw new BindingException(BindingFailure.MalformedInput);
                    Inspect(property.Value);
                }
                break;
            case JsonValueKind.Array:
                if (element.GetArrayLength() > 64) throw new BindingException(BindingFailure.MalformedInput);
                foreach (var child in element.EnumerateArray()) Inspect(child);
                break;
            case JsonValueKind.String:
                if (element.GetString()!.Length > 2048) throw new BindingException(BindingFailure.MalformedInput);
                break;
        }
    }
    private sealed class ExactEnum<T> : JsonConverter<T> where T : struct, Enum
    {
        public override T Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
        {
            if (reader.TokenType == JsonTokenType.String)
                foreach (var value in Enum.GetValues<T>())
                    if (reader.GetString() == JsonNamingPolicy.CamelCase.ConvertName(value.ToString())) return value;
            throw new JsonException();
        }
        public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options)
        {
            if (!Enum.IsDefined(value)) throw new JsonException();
            writer.WriteStringValue(JsonNamingPolicy.CamelCase.ConvertName(value.ToString()));
        }
    }
}
