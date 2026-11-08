
using PersonalFinance.Domain.Entities;

namespace PersonalFinance.Infrastructure.Finance;

public static class BillPaymentIdempotency
{
    public const short CanonicalVersion = 1;

    public static bool Matches(
        BillPaymentReceipt accepted,
        int billId,
        DateOnly dueDate)
    {
        if (accepted.CanonicalVersion != CanonicalVersion)
            throw new FinanceException(
                409,
                "The accepted payment uses an unsupported canonical version.");

        return accepted.BillId == billId
            && accepted.DueDate == dueDate;
    }
}
