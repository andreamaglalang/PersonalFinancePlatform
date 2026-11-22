using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure.Migrations;

[DbContext(typeof(ApplicationDbContext))]
[Migration("20261122090000_AddPlatformJournal")]
public sealed class AddPlatformJournal : Migration
{
    protected override void Up(MigrationBuilder migrationBuilder) => migrationBuilder.Sql("""
        CREATE TABLE dbo.PlatformJournal (
            Id bigint IDENTITY(1,1) NOT NULL CONSTRAINT PK_PlatformJournal PRIMARY KEY,
            ActorId int NOT NULL,
            Action nvarchar(40) NOT NULL,
            TargetType nvarchar(40) NOT NULL,
            TargetId int NOT NULL,
            OccurredAtUtc datetime2 NOT NULL
        );
        CREATE INDEX IX_PlatformJournal_TargetType_OccurredAtUtc
            ON dbo.PlatformJournal (TargetType, OccurredAtUtc DESC, Id DESC)
            INCLUDE (Action, ActorId, TargetId);
        """);

    protected override void Down(MigrationBuilder migrationBuilder) =>
        throw new NotSupportedException("Removing the platform journal would destroy recorded history.");
}
