using PersonalFinance.BillPaymentSqlPreflight;

return await OfflineEntry.RunAsync(args, Console.Out, Console.Error);

namespace PersonalFinance.BillPaymentSqlPreflight
{
    internal static class OfflineEntry
    {
        internal static Task<int> RunAsync(string[] args, TextWriter output, TextWriter error)
        {
            if (args.Length == 2 && args[0] == "--mode" && args[1] == "self-test-offline")
                return PreflightTests.RunAsync(output, error);
            if (args.Length == 0 || (args.Length == 2 && args[0] == "--mode" && args[1] == "info"))
            {
                output.WriteLine("Stage 1: offline fake-based preflight design only. No live adapters exist.");
                output.WriteLine("Only --mode info and --mode self-test-offline are supported.");
                output.WriteLine("No result authorizes database writes. Migration remains UNSAFE TO APPLY.");
                return Task.FromResult(0);
            }
            error.WriteLine("REJECTED: only offline information and self-tests are available.");
            return Task.FromResult(2);
        }
    }
}
