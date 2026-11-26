using System.Net;
using System.Text;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.ViewModels;

internal static class MemberTransactionEditChecks
{
    public static async Task RunAsync()
    {
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "ActivityController.cs"));
        var form = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Create.cshtml"));
        var rows = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Activity", "Index.cshtml"));
        var edit = typeof(ActivityController).GetMethod(nameof(ActivityController.Edit),
            new[] { typeof(int), typeof(MemberTransactionDraftViewModel), typeof(CancellationToken) });
        Check(edit?.IsDefined(typeof(HttpPostAttribute), inherit: true) == true
            && edit.IsDefined(typeof(ValidateAntiForgeryTokenAttribute), inherit: true),
            "The transaction edit POST must be antiforgery protected.");
        Check(controller.Contains("rateIdentityChanged") && controller.Contains("original.CurrencyCode")
            && controller.Contains("original.TransactionDate") && controller.Contains("UpdateTransactionRequest"),
            "An amount-only edit preserves the server's saved rate; currency or date edits use the update contract.");
        Check(form.Contains("asp-route-id=\"@Model.TransactionId\"") && form.Contains("FxConfirmed")
            && form.Contains("name=\"FxProof\""), "The edit form retains its route ID and existing FX confirmation safeguards.");
        Check(rows.Contains("var isProtected = item.BillId is not null || item.BillDueDate is not null;")
            && rows.Contains("if (isProtected)") && rows.Contains("activity-protected"),
            "Bill-payment transactions are not offered for editing.");

        using var handler = new UpdateHandler();
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://api.example.test/") };
        var client = new TransactionApiClient(http);
        var seen = new string('A', 64);
        var result = await client.UpdateAsync(7, new UpdateTransactionRequest("Expense", 1, 12.50m, "PHP",
            new DateOnly(2026, 11, 24), "Fixture edit", seen), "fixture-token", default);
        Check(result is { Status: 200, Value.TransactionId: 7, Value.Amount: 12.50m }
            && handler.Method == HttpMethod.Put && handler.Path == "/api/transactions/7"
            && handler.Authorization == "Bearer fixture-token" && handler.Body!.Contains("12.50"),
            "The existing authenticated PUT transaction contract accepts and validates a successful update response.");
        Check(handler.Body!.Contains("\"version\":\"" + seen + "\""), "C1: the PUT body carries the version the member read.");
        Check(result.Value!.Version == new string('B', 64), "C1: the new version from the API response is surfaced.");
        Check(form.Contains("<input type=\"hidden\" asp-for=\"Version\" />"), "C1: the edit form carries the version it was opened with.");
        Check(controller.Contains("Version = result.Value.Version") && controller.Contains("model.Version!")
            && !controller.Contains("original.Version"), "C1: POST sends the form's version, never the freshly re-read one.");
        Check(controller.Contains("result.Status == 409") && controller.Contains("changed elsewhere"),
            "C1: a stale edit (409) explains the cross-client conflict and asks for a reload.");
        Console.WriteLine("PASS: transaction edit route guards, FX behavior and API PUT transport.");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class UpdateHandler : HttpMessageHandler
    {
        public HttpMethod? Method { get; private set; }
        public string? Path { get; private set; }
        public string? Authorization { get; private set; }
        public string? Body { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Method = request.Method;
            Path = request.RequestUri!.PathAndQuery;
            Authorization = request.Headers.Authorization?.ToString();
            Body = await request.Content!.ReadAsStringAsync(ct);
            const string response = """{"transactionId":7,"clientId":"6fab4ec4-9329-4e80-9c6a-57f32cf82b10","type":"Expense","categoryId":1,"categoryName":"Food","amount":12.50,"currencyCode":"PHP","fxRate":1,"fxRateDate":"2026-11-24","amountBase":12.50,"transactionDate":"2026-11-24","description":"Fixture edit","createdAtUtc":"2026-11-24T00:00:00Z","billId":null,"billDueDate":null,"version":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"}""";
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(response, Encoding.UTF8, "application/json") };
        }
    }
}
