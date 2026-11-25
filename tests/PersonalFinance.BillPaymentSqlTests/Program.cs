using System.Security.Cryptography;
using PersonalFinance.BillPaymentSqlTests;

return BillSqlOfflineEntry.Run(args, Console.Out, Console.Error);

namespace PersonalFinance.BillPaymentSqlTests
{
    public static class BillSqlOfflineEntry
    {
        public static int Run(string[] args, TextWriter output, TextWriter error,
            Func<string, byte[]>? readManifest = null)
        {
            if (args.Length == 2 && args[0] == "--mode" && args[1] == "self-test-offline")
                return BillSqlTargetPolicyTests.Run(output, error);
            if (args.Length != 4 || args[0] != "--mode" || args[1] != "validate-offline"
                || args[2] != "--manifest" || string.IsNullOrWhiteSpace(args[3])
                || args[3].StartsWith("--", StringComparison.Ordinal))
            {
                error.WriteLine("REJECTED: use --mode self-test-offline or --mode validate-offline --manifest <local-path>.");
                return 2;
            }
            try
            {
                var bytes = (readManifest ?? ReadManifest)(args[3]);
                _ = BillSqlTargetPolicy.ParseAndValidate(bytes);
                output.WriteLine("PASS: offline declarations only; no live identity or execution authority verified.");
                output.WriteLine("Manifest SHA-256: " + Convert.ToHexString(SHA256.HashData(bytes)));
                output.WriteLine("No connection, provisioning, migration, startup or cleanup functionality exists.");
                return 0;
            }
            catch (BillSqlPolicyException)
            {
                error.WriteLine("REJECTED: manifest policy or input safety validation failed.");
                return 2;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException
                or NotSupportedException or System.Security.SecurityException)
            {
                error.WriteLine("REJECTED: manifest could not be read safely.");
                return 2;
            }
            catch (Exception)
            {
                error.WriteLine("FAIL: unexpected offline validation failure.");
                return 1;
            }
        }

        public static void ValidateManifestPathSyntax(string path)
        {
            if (string.IsNullOrWhiteSpace(path) || path.Length < 3 || !char.IsAsciiLetter(path[0])
                || path[1] != ':' || path[2] != '\\' || path.AsSpan(2).Contains(':')
                || path.IndexOfAny(['*', '?', '/', '\0', '"', '<', '>', '|']) >= 0
                || path.Split('\\').Skip(1).Any(part => part.Length == 0 || part is "." or ".."
                    || part.EndsWith(' ') || part.EndsWith('.')))
                throw new BillSqlPolicyException("An ordinary absolute local Windows manifest path is required.");
        }

        public static byte[] ReadBounded(Stream stream)
        {
            var buffer = new byte[BillSqlTargetPolicy.MaximumManifestBytes + 1];
            var length = 0;
            while (length < buffer.Length)
            {
                var count = stream.Read(buffer, length, buffer.Length - length);
                if (count == 0) break;
                length += count;
            }
            if (length == 0 || length > BillSqlTargetPolicy.MaximumManifestBytes)
                throw new BillSqlPolicyException("Manifest size is invalid.");
            return buffer[..length];
        }

        private static byte[] ReadManifest(string suppliedPath)
        {
            ValidateManifestPathSyntax(suppliedPath);
            if (!OperatingSystem.IsWindows())
                throw new BillSqlPolicyException("Windows is required for manifest file inspection.");
            var path = Path.GetFullPath(suppliedPath);
            if (!string.Equals(path, suppliedPath, StringComparison.Ordinal)
                || new DriveInfo(path[..3]).DriveType != DriveType.Fixed)
                throw new BillSqlPolicyException("Only an exact fixed-local-drive manifest path is allowed.");
            for (string? component = path; component is not null; component = Path.GetDirectoryName(component))
                if ((File.GetAttributes(component) & FileAttributes.ReparsePoint) != 0)
                    throw new BillSqlPolicyException("Manifest reparse points are prohibited.");
            using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            return ReadBounded(file);
        }
    }
}
