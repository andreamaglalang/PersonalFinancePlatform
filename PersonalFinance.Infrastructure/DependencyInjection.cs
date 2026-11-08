
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Persistence;

namespace PersonalFinance.Infrastructure;

public static class DependencyInjection
{
    public static IServiceCollection AddInfrastructure(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        services.AddDbContext<ApplicationDbContext>(options =>
        {
            var connectionString = configuration.GetConnectionString("DefaultConnection");

            if (string.IsNullOrWhiteSpace(connectionString))
                options.UseSqlServer();
            else
                options.UseSqlServer(connectionString);
        });

        services.AddIdentityCore<ApplicationUser>(options =>
        {
            options.User.RequireUniqueEmail = true;

            options.Password.RequiredLength = 12;
            options.Password.RequiredUniqueChars = 4;
            options.Password.RequireUppercase = true;
            options.Password.RequireLowercase = true;
            options.Password.RequireDigit = true;
            options.Password.RequireNonAlphanumeric = true;

            options.Lockout.AllowedForNewUsers = true;
            options.Lockout.MaxFailedAccessAttempts = 5;
            options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(15);
        })
            .AddRoles<IdentityRole<int>>()
            .AddEntityFrameworkStores<ApplicationDbContext>()
            .AddDefaultTokenProviders();

        services.AddSingleton<TimeProvider>(TimeProvider.System);
        services.AddSingleton<DummyPasswordVerifier>();

        services.AddScoped<IAuthSessionStore, SqlAuthSessionStore>();
        services.AddScoped<IAuthenticationService, AuthenticationService>();
        services.AddScoped<IRefreshTokenService, RefreshTokenService>();

        services.AddSingleton<IFrankfurterClient>(provider =>
        {
            var http = new HttpClient(
                new SocketsHttpHandler
                {
                    PooledConnectionLifetime = TimeSpan.FromMinutes(5)
                })
            {
                BaseAddress = new Uri("https://api.frankfurter.dev/v2/"),
                Timeout = TimeSpan.FromSeconds(5),
                MaxResponseContentBufferSize = 256 * 1024
            };

            http.DefaultRequestHeaders.Accept.ParseAdd("application/json");

            return new FrankfurterClient(
                http,
                provider.GetRequiredService<TimeProvider>());
        });

        services.AddScoped<IFinancialWriteScope, SqlFinancialWriteScope>();
        services.AddScoped<IConversionService, ConversionService>();
        services.AddScoped<ITransactionService, TransactionService>();
        services.AddScoped<ICategoryService, CategoryService>();
        services.AddScoped<IBudgetUsageService, BudgetUsageService>();
        services.AddScoped<IBudgetService, BudgetService>();
        services.AddScoped<ISavingsGoalService, SavingsGoalService>();

        services.AddOptions<CurrencyPolicyOptions>()
            .Bind(configuration.GetSection(CurrencyPolicyOptions.SectionName))
            .Validate(
                x => x.MaximumRateAgeDays >= 0,
                "MaximumRateAgeDays cannot be negative.");

        return services;
    }
}
