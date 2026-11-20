using System.Text.RegularExpressions;

namespace PersonalFinance.Infrastructure.Finance.Contracts
{
    public sealed class ExchangeRateQuoteQuery
    {
        public string? From { get; set; }
        public string? To { get; set; }
        public decimal? Amount { get; set; }
        /// <summary>Rate date; defaults to today. Future dates are rejected.</summary>
        public DateOnly? Date { get; set; }
    }

    /// <summary>
    /// A read-only quote. Rate (8 decimals) and converted amount (2 decimals, away from zero) come from the same
    /// conversion service and policy that transactions use; nothing is saved as a transaction.
    /// </summary>
    public sealed record ExchangeRateQuoteResponse(
        string From, string To, decimal Amount, decimal Rate, DateOnly RateDate, DateOnly RequestedDate,
        decimal ConvertedAmount, string Source, DateTime RetrievedAtUtc);
}

namespace PersonalFinance.Infrastructure.Finance
{
    using PersonalFinance.Infrastructure.Finance.Contracts;

    public interface IExchangeRateQuoteService
    {
        Task<FinanceResult<ExchangeRateQuoteResponse>> QuoteAsync(ExchangeRateQuoteQuery query, CancellationToken ct);
    }

    /// <summary>
    /// Read-only exchange-rate quote for signed-in users. Delegates to <see cref="IConversionService"/>, so the
    /// provider (Frankfurter), the maximum rate age, rate normalisation and amount rounding are identical to
    /// transaction saving. Like transaction conversion, a newly fetched provider rate is cached in ExchangeRates.
    /// It never creates or changes transactions; transaction saves still validate their own rate.
    /// </summary>
    public sealed partial class ExchangeRateQuoteService(IConversionService conversion, TimeProvider clock) : IExchangeRateQuoteService
    {
        public const string ProviderSource = "Frankfurter";
        public const string SameCurrencySource = "Same currency";
        private static readonly DateOnly Earliest = new(1999, 1, 4); // first ECB reference rate

        [GeneratedRegex("^[A-Z]{3}$")]
        private static partial Regex Code();

        public async Task<FinanceResult<ExchangeRateQuoteResponse>> QuoteAsync(ExchangeRateQuoteQuery query, CancellationToken ct)
        {
            if (query.From is null || query.To is null || !Code().IsMatch(query.From) || !Code().IsMatch(query.To))
                return FinanceResult<ExchangeRateQuoteResponse>.Failure(400, "Three-letter uppercase currency codes are required.");
            if (query.Amount is not { } amount)
                return FinanceResult<ExchangeRateQuoteResponse>.Failure(400, "An amount is required.");
            var today = DateOnly.FromDateTime(clock.GetLocalNow().DateTime);
            var date = query.Date ?? today;
            if (date > today) return FinanceResult<ExchangeRateQuoteResponse>.Failure(400, "Quotes are available for today or earlier.");
            if (date < Earliest) return FinanceResult<ExchangeRateQuoteResponse>.Failure(400, "No reference rates exist for that date.");
            try
            {
                var snapshot = await conversion.ConvertAsync(amount, query.From, query.To, date, ct);
                return FinanceResult<ExchangeRateQuoteResponse>.Success(new ExchangeRateQuoteResponse(
                    query.From, query.To, amount, snapshot.Rate, snapshot.RateDate, date, snapshot.AmountBase,
                    query.From == query.To ? SameCurrencySource : ProviderSource, clock.GetUtcNow().UtcDateTime));
            }
            catch (FinanceException ex)
            {
                return FinanceResult<ExchangeRateQuoteResponse>.Failure(ex.StatusCode, ex.Message);
            }
        }
    }
}
