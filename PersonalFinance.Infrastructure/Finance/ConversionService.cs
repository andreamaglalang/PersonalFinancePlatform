using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Finance;

public sealed class ConversionService(
    ApplicationDbContext db, IFrankfurterClient provider, IOptions<CurrencyPolicyOptions> options,
    TimeProvider clock) : IConversionService
{
    public async Task<ConversionSnapshot> ConvertAsync(decimal amount, string source, string target, DateOnly date, CancellationToken ct)
    {
        FinanceValidation.ValidateMoney(amount);
        if (!FinanceValidation.CurrencyCode(source) || !FinanceValidation.CurrencyCode(target))
            throw new FinanceException(400, "A three-letter uppercase currency code is required.");
        if (source == target)
            return new ConversionSnapshot(1m, date, FinanceValidation.ConvertAmount(amount, 1m));

        var age = options.Value.MaximumRateAgeDays;
        var minimum = DateOnly.FromDayNumber((int)Math.Max(0L, (long)date.DayNumber - age));
        var cached = await db.ExchangeRates.AsNoTracking()
            .Where(r => r.BaseCurrency == source && r.QuoteCurrency == target && r.RateDate <= date && r.RateDate >= minimum)
            .OrderByDescending(r => r.RateDate).FirstOrDefaultAsync(ct);
        if (cached is not null)
            return Snapshot(amount, new(source, target, cached.RateDate, cached.Rate), date, age);

        if (!await provider.SupportsAsync(source, ct) || !await provider.SupportsAsync(target, ct))
            throw new FinanceException(400, "The currency is not supported by the exchange-rate provider.");

        var quote = await provider.GetRateAsync(source, target, date, ct);
        if (quote.BaseCurrency != source || quote.QuoteCurrency != target)
            throw new FinanceException(503, "No acceptable exchange rate is available.");
        var snapshot = Snapshot(amount, quote, date, age);
        var row = new ExchangeRate
        {
            BaseCurrency = source, QuoteCurrency = target, RateDate = snapshot.RateDate,
            Rate = snapshot.Rate, FetchedAtUtc = clock.GetUtcNow().UtcDateTime
        };
        try
        {
            db.ExchangeRates.Add(row);
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException ex) when (ex.InnerException is SqlException { Number: 2601 or 2627 })
        {
            db.Entry(row).State = EntityState.Detached;
            var winner = await db.ExchangeRates.AsNoTracking()
                .SingleAsync(r => r.BaseCurrency == source && r.QuoteCurrency == target && r.RateDate == snapshot.RateDate, ct);
            return Snapshot(amount, new(source, target, winner.RateDate, winner.Rate), date, age);
        }
        return snapshot;
    }

    private static ConversionSnapshot Snapshot(decimal amount, FxQuote quote, DateOnly date, int maximumAge)
    {
        var age = date.DayNumber - quote.RateDate.DayNumber;
        if (age < 0 || age > maximumAge)
            throw new FinanceException(503, "No acceptable exchange rate is available.");
        var roundedRate = FinanceValidation.NormalizeRate(quote.Rate);
        return new ConversionSnapshot(roundedRate, quote.RateDate, FinanceValidation.ConvertAmount(amount, roundedRate));
    }
}

