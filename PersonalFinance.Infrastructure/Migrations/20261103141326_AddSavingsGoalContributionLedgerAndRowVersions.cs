using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace PersonalFinance.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddSavingsGoalContributionLedgerAndRowVersions : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<byte[]>(
                name: "RowVersion",
                table: "SavingsGoals",
                type: "rowversion",
                rowVersion: true,
                nullable: false,
                defaultValue: new byte[0]);

            migrationBuilder.AddColumn<byte[]>(
                name: "RowVersion",
                table: "GoalContributions",
                type: "rowversion",
                rowVersion: true,
                nullable: false,
                defaultValue: new byte[0]);

            migrationBuilder.CreateTable(
                name: "GoalContributionRequests",
                columns: table => new
                {
                    RequestId = table.Column<long>(type: "bigint", nullable: false)
                        .Annotation("SqlServer:Identity", "1, 1"),
                    UserId = table.Column<int>(type: "int", nullable: false),
                    ClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: true),
                    GoalId = table.Column<int>(type: "int", nullable: false),
                    ContributionId = table.Column<int>(type: "int", nullable: false),
                    OriginalAmount = table.Column<decimal>(type: "decimal(18,2)", precision: 18, scale: 2, nullable: false),
                    OriginalContributedOn = table.Column<DateOnly>(type: "date", nullable: false),
                    OriginalNote = table.Column<string>(type: "nvarchar(200)", maxLength: 200, nullable: true),
                    CanonicalVersion = table.Column<short>(type: "smallint", nullable: false),
                    RecordedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: false),
                    DeletedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_GoalContributionRequests", x => x.RequestId);
                    table.CheckConstraint("CK_GoalContributionRequests_Amount", "[OriginalAmount] > 0");
                    table.CheckConstraint("CK_GoalContributionRequests_CanonicalVersion", "[CanonicalVersion] = 1");
                    table.CheckConstraint("CK_GoalContributionRequests_ClientId", "[ClientId] IS NULL OR [ClientId] <> '00000000-0000-0000-0000-000000000000'");
                    table.CheckConstraint("CK_GoalContributionRequests_ContributionId", "[ContributionId] > 0");
                    table.CheckConstraint("CK_GoalContributionRequests_DeletedAtUtc", "[DeletedAtUtc] IS NULL OR [DeletedAtUtc] >= [RecordedAtUtc]");
                    table.ForeignKey(
                        name: "FK_GoalContributionRequests_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id");
                    table.ForeignKey(
                        name: "FK_GoalContributionRequests_SavingsGoals_GoalId",
                        column: x => x.GoalId,
                        principalTable: "SavingsGoals",
                        principalColumn: "GoalId");
                });

            migrationBuilder.CreateIndex(
                name: "IX_GoalContributionRequests_ContributionId",
                table: "GoalContributionRequests",
                column: "ContributionId",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_GoalContributionRequests_GoalId",
                table: "GoalContributionRequests",
                column: "GoalId");

            migrationBuilder.CreateIndex(
                name: "IX_GoalContributionRequests_UserId_ClientId",
                table: "GoalContributionRequests",
                columns: new[] { "UserId", "ClientId" },
                unique: true,
                filter: "[ClientId] IS NOT NULL");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "GoalContributionRequests");

            migrationBuilder.DropColumn(
                name: "RowVersion",
                table: "SavingsGoals");

            migrationBuilder.DropColumn(
                name: "RowVersion",
                table: "GoalContributions");
        }
    }
}
