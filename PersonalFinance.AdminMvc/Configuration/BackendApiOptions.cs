namespace PersonalFinance.AdminMvc.Configuration;

public sealed class BackendApiOptions
{
    public const string Section = "BackendApi";
    public string BaseUrl { get; set; } = string.Empty;

    public static bool IsValid(BackendApiOptions options) =>
        Uri.TryCreate(options.BaseUrl, UriKind.Absolute, out var uri)
        && uri.IsWellFormedOriginalString()
        && uri.Scheme == Uri.UriSchemeHttps && !string.IsNullOrEmpty(uri.Host)
        && string.IsNullOrEmpty(uri.UserInfo) && string.IsNullOrEmpty(uri.Query)
        && string.IsNullOrEmpty(uri.Fragment) && uri.AbsolutePath == "/"
        // Uri normalizes /path/.. to /. Check the original text as well: configuration
        // must contain an origin, not a path that happens to normalize to its root.
        && HasOnlyRootPath(options.BaseUrl)
        && !options.BaseUrl.Contains('\\')
        && !options.BaseUrl.Contains('?') && !options.BaseUrl.Contains('#');

    private static bool HasOnlyRootPath(string value)
    {
        var firstPathSlash = value.IndexOf('/', "https://".Length);
        return firstPathSlash < 0 || firstPathSlash == value.Length - 1;
    }
}
