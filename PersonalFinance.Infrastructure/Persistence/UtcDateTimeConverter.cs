using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace PersonalFinance.Infrastructure.Persistence;

internal sealed class UtcDateTimeConverter : ValueConverter<DateTime, DateTime>
{
    public UtcDateTimeConverter() : base(value => RequireUtc(value), value => DateTime.SpecifyKind(value, DateTimeKind.Utc)) { }

    private static DateTime RequireUtc(DateTime value) =>
        value.Kind == DateTimeKind.Utc
            ? value
            : throw new ArgumentException("UTC timestamp properties must be supplied with DateTimeKind.Utc.");
}

