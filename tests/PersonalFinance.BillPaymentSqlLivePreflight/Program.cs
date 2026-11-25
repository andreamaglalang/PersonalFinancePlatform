using PersonalFinance.BillPaymentSqlLivePreflight;

return ReviewOfflineEntry.Run(args, Console.Out, Console.Error);

namespace PersonalFinance.BillPaymentSqlLivePreflight
{
    internal static class ReviewOfflineEntry
    {
        internal static int Run(string[] args, TextWriter output, TextWriter error)
        {
            if (args.Length == 2 && args[0] == "--mode" && args[1] == "self-test-offline")
                return ReviewPacketTests.Run(output, error);
            if (args.Length == 0 || (args.Length == 2 && args[0] == "--mode" && args[1] == "info"))
            {
                output.WriteLine("Stage 2B: offline review-packet consistency only; human approval is external.");
                output.WriteLine("No live adapters or dispatch exist. Migration remains UNSAFE TO APPLY.");
                return 0;
            }
            error.WriteLine("REJECTED: only --mode info or --mode self-test-offline is supported.");
            return 2;
        }
    }
}
