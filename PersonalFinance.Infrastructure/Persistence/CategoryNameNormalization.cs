namespace PersonalFinance.Infrastructure.Persistence;

/// <summary>Fixed boundary trimming only. SQL Server owns linguistic equality.</summary>
public static class CategoryNameNormalization
{
    public const string PropertyName = "NormalizedName";
    public const string ComparisonCollation = "Latin1_General_100_CI_AS_KS_WS_SC";
    public const string UniqueIndexName = "UX_Categories_UserId_Type_NormalizedName";
    public const string SystemWriteLock = "finance:categories:system";
    private static readonly char[] BoundaryCharacters =
    [
        '\u0009','\u000A','\u000B','\u000C','\u000D','\u0020','\u0085','\u00A0','\u1680',
        '\u2000','\u2001','\u2002','\u2003','\u2004','\u2005','\u2006','\u2007',
        '\u2008','\u2009','\u200A','\u2028','\u2029','\u202F','\u205F','\u3000'
    ];
    public static string Trim(string name) => name.Trim(BoundaryCharacters);

    public const string ComputedSql = """
        CONVERT(nvarchar(50),
            TRIM(
                CONVERT(nvarchar(25),
                    NCHAR(9) + NCHAR(10) + NCHAR(11) + NCHAR(12) + NCHAR(13) +
                    NCHAR(32) + NCHAR(133) + NCHAR(160) + NCHAR(5760) +
                    NCHAR(8192) + NCHAR(8193) + NCHAR(8194) + NCHAR(8195) +
                    NCHAR(8196) + NCHAR(8197) + NCHAR(8198) + NCHAR(8199) +
                    NCHAR(8200) + NCHAR(8201) + NCHAR(8202) +
                    NCHAR(8232) + NCHAR(8233) + NCHAR(8239) +
                    NCHAR(8287) + NCHAR(12288)
                ) COLLATE Latin1_General_100_BIN2
                FROM [Name] COLLATE Latin1_General_100_BIN2
            )
        ) COLLATE Latin1_General_100_CI_AS_KS_WS_SC
        """;
}
