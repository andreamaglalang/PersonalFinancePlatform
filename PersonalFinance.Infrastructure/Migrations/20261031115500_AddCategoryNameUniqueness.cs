using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace PersonalFinance.Infrastructure.Migrations
{
    /// <inheritdoc />
    public partial class AddCategoryNameUniqueness : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "NormalizedName",
                table: "Categories",
                type: "nvarchar(50)",
                maxLength: 50,
                nullable: true,
                computedColumnSql: "CONVERT(nvarchar(50),\n    TRIM(\n        CONVERT(nvarchar(25),\n            NCHAR(9) + NCHAR(10) + NCHAR(11) + NCHAR(12) + NCHAR(13) +\n            NCHAR(32) + NCHAR(133) + NCHAR(160) + NCHAR(5760) +\n            NCHAR(8192) + NCHAR(8193) + NCHAR(8194) + NCHAR(8195) +\n            NCHAR(8196) + NCHAR(8197) + NCHAR(8198) + NCHAR(8199) +\n            NCHAR(8200) + NCHAR(8201) + NCHAR(8202) +\n            NCHAR(8232) + NCHAR(8233) + NCHAR(8239) +\n            NCHAR(8287) + NCHAR(12288)\n        ) COLLATE Latin1_General_100_BIN2\n        FROM [Name] COLLATE Latin1_General_100_BIN2\n    )\n) COLLATE Latin1_General_100_CI_AS_KS_WS_SC",
                stored: true);

            migrationBuilder.CreateIndex(
                name: "UX_Categories_UserId_Type_NormalizedName",
                table: "Categories",
                columns: new[] { "UserId", "Type", "NormalizedName" },
                unique: true)
                .Annotation("SqlServer:Clustered", false);

            migrationBuilder.AddCheckConstraint(
                name: "CK_Categories_NormalizedName",
                table: "Categories",
                sql: "[NormalizedName] IS NOT NULL AND DATALENGTH([NormalizedName]) > 0");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "UX_Categories_UserId_Type_NormalizedName",
                table: "Categories");

            migrationBuilder.DropCheckConstraint(
                name: "CK_Categories_NormalizedName",
                table: "Categories");

            migrationBuilder.DropColumn(
                name: "NormalizedName",
                table: "Categories");
        }
    }
}
