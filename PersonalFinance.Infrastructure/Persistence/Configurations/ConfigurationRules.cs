using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal static class ConfigurationRules
{
    internal const string BinaryCollation = "Latin1_General_100_BIN2";
    internal static string CurrencyCheck(string column) =>
        $"DATALENGTH([{column}]) = 3 AND [{column}] COLLATE {BinaryCollation} NOT LIKE '%[^A-Z]%'";
    internal static void Currency(PropertyBuilder<string> property) =>
        property.HasColumnType("char(3)").IsRequired();
    internal static void Money(PropertyBuilder<decimal> property) => property.HasPrecision(18, 2);
}

