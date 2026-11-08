using PersonalFinance.Infrastructure.Finance;
namespace PersonalFinance.FinanceTests;
internal sealed class MemoryWriteScope : IFinancialWriteScope
{
    private readonly SemaphoreSlim gate = new(1); public Func<Task>? BeforeWrite;
    public async Task<T> ExecuteAsync<T>(IEnumerable<string> resources, Func<CancellationToken,Task<T>> operation, CancellationToken ct)
    { await gate.WaitAsync(ct); try { if(BeforeWrite is not null) { var hook=BeforeWrite; BeforeWrite=null; await hook(); } return await operation(ct); } finally { gate.Release(); } }
}
internal sealed class FakeFx : IFrankfurterClient
{
    public int Calls; public decimal Rate = 56.123456785m; public int Age; public bool Unavailable;
    public Task<bool> SupportsAsync(string code,CancellationToken ct) => Task.FromResult(code is "USD" or "PHP" or "EUR");
    public Task<FxQuote> GetRateAsync(string source,string target,DateOnly date,CancellationToken ct)
    { Calls++; if(Unavailable) throw new FinanceException(503,"Unavailable"); return Task.FromResult(new FxQuote(source,target,date.AddDays(-Age),Rate)); }
}

internal sealed class FakeHttp(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
{
 public int Calls;
 protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,CancellationToken ct)
 { Calls++; return Task.FromResult(respond(request)); }
}