using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Infrastructure.Identity;

namespace PersonalFinance.Infrastructure.Persistence.Configurations;

internal sealed class DeletedTransactionConfiguration : IEntityTypeConfiguration<DeletedTransaction>
{
    public void Configure(EntityTypeBuilder<DeletedTransaction> b)
    {
        b.HasKey(x => new { x.UserId, x.ClientId });
        b.HasOne<ApplicationUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.NoAction);
        // No FK to Transactions: the identity deliberately outlives the financial row.
        b.ToTable("DeletedTransactions", t =>
        {
            t.HasCheckConstraint("CK_DeletedTransactions_ClientId", "[ClientId] <> '00000000-0000-0000-0000-000000000000'");
            t.HasCheckConstraint("CK_DeletedTransactions_TransactionId", "[TransactionId] > 0");
        });
    }
}
