using System.Collections.Concurrent;
using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using PersonalFinance.AdminMvc.Services.Contracts;

namespace PersonalFinance.AdminMvc.Authentication;

public sealed class AdminSession
{
    public required string Id { get; init; }
    public required AuthenticationResponse Tokens { get; set; }
    public required CurrentUserResponse User { get; set; }
    public required DateTimeOffset AbsoluteExpiry { get; init; }
    public DateTimeOffset LastActivity { get; set; } = DateTimeOffset.UtcNow;
    /// <summary>When the API last confirmed this session (GET api/auth/me); null until the first check.</summary>
    public DateTimeOffset? LastVerifiedAt { get; set; }
    public AuthenticationTicket? Ticket { get; set; }
    public SemaphoreSlim Gate { get; } = new(1, 1);
    public bool Invalidated { get; set; }
}

// One authoritative record holds both the ticket and tokens. No disk or database persistence.
public sealed class AdminSessionStore : ITicketStore, IDisposable
{
    public const string SessionClaim = "owli:session";
    public const string Scheme = "AdminCookie";
    public static readonly TimeSpan IdleLimit = TimeSpan.FromMinutes(20);
    private const int Capacity = 256;
    private readonly ConcurrentDictionary<string, AdminSession> sessions = new();
    private readonly object admission = new();
    private readonly Timer cleanup;

    public AdminSessionStore() => cleanup = new Timer(_ => Sweep(), null,
        TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));

    public AdminSession? Create(AuthenticationResponse tokens, CurrentUserResponse user)
    {
        lock (admission)
        {
            Sweep();
            if (sessions.Count >= Capacity) return null;
            var session = new AdminSession
            {
                Id = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)), Tokens = tokens, User = user,
                AbsoluteExpiry = new[] { DateTimeOffset.UtcNow.AddHours(8),
                    new DateTimeOffset(tokens.RefreshTokenExpiresAtUtc) }.Min()
            };
            return sessions.TryAdd(session.Id, session) ? session : null;
        }
    }

    public AdminSession? Find(string? id) => id is not null && sessions.TryGetValue(id, out var value) ? value : null;
    // Call while holding this record's Gate, including after every awaited API operation.
    public bool IsCurrent(AdminSession value) => !value.Invalidated
        && ReferenceEquals(Find(value.Id), value)
        && DateTimeOffset.UtcNow < value.AbsoluteExpiry
        && DateTimeOffset.UtcNow - value.LastActivity < IdleLimit
        && DateTime.UtcNow < value.Tokens.RefreshTokenExpiresAtUtc;

    public void Invalidate(AdminSession value)
    {
        value.Invalidated = true;
        sessions.TryRemove(value.Id, out _);
        value.Ticket = null;
        // Drop secret-bearing references as soon as possible; strings cannot be securely zeroed.
        value.Tokens = null!;
    }

    public async Task<string> StoreAsync(AuthenticationTicket ticket)
    {
        var value = Find(ticket.Principal.FindFirstValue(SessionClaim))
            ?? throw new InvalidOperationException("The administrator session is unavailable.");
        await value.Gate.WaitAsync();
        try
        {
            if (!IsCurrent(value)) throw new InvalidOperationException("The administrator session expired.");
            value.Ticket = ticket;
            return value.Id;
        }
        finally { value.Gate.Release(); }
    }

    public async Task<AuthenticationTicket?> RetrieveAsync(string key)
    {
        var value = Find(key);
        if (value is null) return null;
        await value.Gate.WaitAsync();
        try
        {
            if (IsCurrent(value)) return value.Ticket;
            Invalidate(value);
            return null;
        }
        finally { value.Gate.Release(); }
    }

    // Sliding cookie renewal is disabled; local idle extension happens only after backend validation.
    public Task RenewAsync(string key, AuthenticationTicket ticket) => Task.CompletedTask;

    public async Task RemoveAsync(string key)
    {
        var value = Find(key);
        if (value is null) return;
        await value.Gate.WaitAsync();
        try { Invalidate(value); }
        finally { value.Gate.Release(); }
    }

    private void Sweep()
    {
        foreach (var value in sessions.Values)
        {
            if (!value.Gate.Wait(0)) continue;
            try { if (!IsCurrent(value)) Invalidate(value); }
            finally { value.Gate.Release(); }
        }
    }

    public void Dispose() => cleanup.Dispose();
}
