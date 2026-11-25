using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PersonalFinance.BillPaymentSqlLivePreflight;

internal static class ReviewPacketParser
{
    internal const int MaximumBytes = 65_536;
    internal static ParsedReviewPacket Parse(ReadOnlyMemory<byte> bytes) => ParsedReviewPacket.Parse(bytes);
}

internal sealed class ParsedReviewPacket
{
    internal ProposedReviewPacket Packet { get; }
    internal string PacketSha256 { get; }

    private ParsedReviewPacket(ProposedReviewPacket packet, string packetSha256)
    {
        Packet = packet;
        PacketSha256 = packetSha256;
    }

    internal static ParsedReviewPacket Parse(ReadOnlyMemory<byte> bytes)
    {
        if (bytes.Length is <= 0 or > ReviewPacketParser.MaximumBytes)
            throw new ReviewPacketException(ReviewFailure.MalformedPacket);
        var snapshot = bytes.ToArray();
        try
        {
            using var document = JsonDocument.Parse(snapshot, new JsonDocumentOptions
            {
                MaxDepth = 12, AllowTrailingCommas = false, CommentHandling = JsonCommentHandling.Disallow
            });
            if (document.RootElement.ValueKind != JsonValueKind.Object)
                throw new ReviewPacketException(ReviewFailure.MalformedPacket);
            RejectDuplicates(document.RootElement);
            var packet = JsonSerializer.Deserialize<ProposedReviewPacket>(snapshot.AsSpan(), new JsonSerializerOptions
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
                PropertyNameCaseInsensitive = false,
                UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
                NumberHandling = JsonNumberHandling.Strict,
                RespectNullableAnnotations = true,
                RespectRequiredConstructorParameters = true,
                MaxDepth = 12
            }) ?? throw new ReviewPacketException(ReviewFailure.MalformedPacket);
            return new(packet, Convert.ToHexString(SHA256.HashData(snapshot)));
        }
        catch (JsonException) { throw new ReviewPacketException(ReviewFailure.MalformedPacket); }
    }

    private static void RejectDuplicates(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw new ReviewPacketException(ReviewFailure.MalformedPacket);
                RejectDuplicates(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var child in element.EnumerateArray()) RejectDuplicates(child);
    }
}
