using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class ExchangeRateConfiguration : IEntityTypeConfiguration<ExchangeRate>
{
    public void Configure(EntityTypeBuilder<ExchangeRate> b)
    {
        b.HasKey(x => x.RateId);
        ConfigurationRules.Currency(b.Property(x => x.BaseCurrency));
        ConfigurationRules.Currency(b.Property(x => x.QuoteCurrency));
        b.Property(x => x.Rate).HasPrecision(18, 8);
        b.HasIndex(x => new { x.BaseCurrency, x.QuoteCurrency, x.RateDate }).IsUnique();
        b.ToTable("ExchangeRates", t =>
        {
            t.HasCheckConstraint("CK_ExchangeRates_Rate", "[Rate] > 0");
            t.HasCheckConstraint("CK_ExchangeRates_BaseCurrency", ConfigurationRules.CurrencyCheck("BaseCurrency"));
            t.HasCheckConstraint("CK_ExchangeRates_QuoteCurrency", ConfigurationRules.CurrencyCheck("QuoteCurrency"));
        });
    }
}

