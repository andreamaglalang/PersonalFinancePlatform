using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace PersonalFinance.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class RetainDeletedTransactionIdentities : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "DeletedTransactions",
                columns: table => new
                {
                    UserId = table.Column<int>(type: "int", nullable: false),
                    ClientId = table.Column<Guid>(type: "uniqueidentifier", nullable: false),
                    TransactionId = table.Column<int>(type: "int", nullable: false),
                    DeletedAtUtc = table.Column<DateTime>(type: "datetime2", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_DeletedTransactions", x => new { x.UserId, x.ClientId });
                    table.CheckConstraint("CK_DeletedTransactions_ClientId", "[ClientId] <> '00000000-0000-0000-0000-000000000000'");
                    table.CheckConstraint("CK_DeletedTransactions_TransactionId", "[TransactionId] > 0");
                    table.ForeignKey(
                        name: "FK_DeletedTransactions_AspNetUsers_UserId",
                        column: x => x.UserId,
                        principalTable: "AspNetUsers",
                        principalColumn: "Id");
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            throw new InvalidOperationException("Deletion identities are permanent retry safeguards. Restore only through an approved data-preserving recovery plan.");
        }
    }
}
