using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Api.Persistence;

/// <summary>Uses the API's environment-specific configuration without opening a database connection.</summary>
public sealed class ApplicationDbContextFactory : IDesignTimeDbContextFactory<ApplicationDbContext>
{
    public ApplicationDbContext CreateDbContext(string[] args)
    {
        // Match normal API configuration precedence: JSON, development user secrets
        // when configured, environment variables, then command-line overrides.
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = args,
            ApplicationName = typeof(ApplicationDbContextFactory).Assembly.GetName().Name,
            ContentRootPath = FindContentRoot()
        });

        var options = new DbContextOptionsBuilder<ApplicationDbContext>();
        var connectionString = builder.Configuration.GetConnectionString("DefaultConnection");
        if (string.IsNullOrWhiteSpace(connectionString))
            options.UseSqlServer(); // Still permits offline model inspection without a configured server.
        else
            options.UseSqlServer(connectionString);

        return new ApplicationDbContext(options.Options);
    }

    private static string FindContentRoot()
    {
        var workingDirectory = Directory.GetCurrentDirectory();
        if (File.Exists(Path.Combine(workingDirectory, "PersonalFinance.Api.csproj")))
            return workingDirectory;

        var apiDirectory = Path.Combine(workingDirectory, "PersonalFinance.Api");
        if (File.Exists(Path.Combine(apiDirectory, "PersonalFinance.Api.csproj")))
            return apiDirectory;

        // Published/compiled tooling and tests receive the API JSON files in their output directory.
        return AppContext.BaseDirectory;
    }
}

