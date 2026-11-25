using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using PersonalFinance.AdminMvc.Services;

internal static class BillPaymentResponseChecks
{
    public static async Task RunAsync()
    {
        var checks = 0;
        var request = new PayBillRequest(Guid.Parse("11111111-1111-1111-1111-111111111111"), new(2026, 11, 23));
        const string body = """
            {"receipt":{"receiptId":1,"clientId":"11111111-1111-1111-1111-111111111111",
            "billId":1,"transactionId":52,"dueDate":"2026-11-23","categoryId":1,"amount":1,
            "currencyCode":"PHP","fxRate":1,"fxRateDate":"2026-11-23","amountBase":1,
            "baseCurrency":"PHP","paidOn":"2026-11-23","canonicalVersion":1,
            "recordedAtUtc":"2026-11-23T11:00:00Z"},"replayed":false,
            "affectedBudgets":[{"budgetId":2,"categoryId":1,"categoryName":"Food & Dining",
            "isCategoryActive":true,"periodMonth":"2026-11-01","limitAmount":2700,
            "alertThresholdPct":80,"spentAmount":2422,"remainingAmount":278,"status":"Warning","baseCurrency":"PHP"}]}
            """;

        async Task<RecurringBillResult<BillPaymentResponse>> Call(HttpStatusCode status, string payload,
            string contentType = "application/json", Exception? failure = null)
        {
            using var handler = new FixtureHandler(status, payload, contentType, failure);
            using var http = new HttpClient(handler) { BaseAddress = new("https://fixture.invalid/") };
            var result = await new RecurringBillApiClient(http).PayAsync(1, request, "fixture-only", default);
            Check(handler.Calls == 1 && handler.Method == HttpMethod.Post
                && handler.Path == "/api/recurring-bills/1/payments"
                && handler.Authorization == "Bearer fixture-only",
                "A payment must make exactly one POST to the requested endpoint with its caller's bearer.");
            using var json = JsonDocument.Parse(handler.Body!);
            Check(json.RootElement.EnumerateObject().Count() == 2
                && json.RootElement.GetProperty("clientId").GetGuid() == request.ClientId
                && json.RootElement.GetProperty("dueDate").GetString() == "2026-11-23",
                "The approved request shape, ClientId and due date must be sent unchanged.");
            checks++;
            return result;
        }

        var created = await Call(HttpStatusCode.Created, body);
        Check(created.Succeeded && created.Status == 201 && created.Value is
            { Replayed: false, Receipt.BillId: 1, Receipt.TransactionId: 52, Receipt.Amount: 1m,
                Receipt.BaseCurrency: "PHP", AffectedBudgets.Count: 1 }
            && created.Value.Receipt.ClientId == request.ClientId,
            "HTTP 201 receipt object must be accepted, including affected budgets and without an internal UserId.");

        foreach (var omitted in new[] { false, true })
        {
            var replay = JsonNode.Parse(body)!.AsObject();
            replay["replayed"] = true;
            if (omitted) replay.Remove("affectedBudgets"); else replay["affectedBudgets"] = null;
            var result = await Call(HttpStatusCode.OK, replay.ToJsonString());
            Check(result.Succeeded && result.Status == 200 && result.Value is
                { Replayed: true, AffectedBudgets: null } && result.Value.Receipt.ClientId == request.ClientId,
                "HTTP 200 replay must accept null or omitted affectedBudgets and retain the original receipt.");
        }

        foreach (var status in new[] { 400, 401, 403, 404, 409, 429 })
        {
            var result = await Call((HttpStatusCode)status, "{\"title\":\"Fixture rejection\"}");
            Check(result.Status == status && !result.Succeeded && result.Value is null,
                $"HTTP {status} must stay a failure without retry or fabricated success.");
        }
        foreach (var status in new[] { 500, 503, 202, 204 })
        {
            var result = await Call((HttpStatusCode)status, "{}");
            Check(result.Status == 503 && !result.Succeeded && result.Value is null,
                $"HTTP {status} must not masquerade as an accepted payment.");
        }
        foreach (var payload in new[] { "true", "{", "{}", "{\"receipt\":null,\"replayed\":false}",
            body.Replace("\"amount\":1", "\"amount\":0"),
            body.Replace("\"billId\":1", "\"billId\":2"),
            body.Replace("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"),
            body.Replace("\"dueDate\":\"2026-11-23\"", "\"dueDate\":\"2026-11-22\""),
            body.Replace("\"canonicalVersion\":1", "\"canonicalVersion\":2"),
            body.Replace("\"status\":\"Warning\"", "\"status\":\"Invalid\"") })
        {
            var result = await Call(HttpStatusCode.Created, payload);
            Check(result.Status == 503 && !result.Succeeded && result.Value is null,
                "Malformed or mismatched receipts and budgets must remain rejected.");
        }
        var wrongType = await Call(HttpStatusCode.Created, body, "text/html");
        Check(wrongType.Status == 503 && !wrongType.Succeeded, "Non-JSON success bodies are not payment confirmation.");
        foreach (var failure in new Exception[] { new HttpRequestException("Fixture offline"),
            new TaskCanceledException("Fixture timeout") })
        {
            var result = await Call(HttpStatusCode.Created, body, failure: failure);
            Check(result.Status == 503 && !result.Succeeded,
                "Transport failures must remain uncertain failures without any automatic retry.");
        }
        Console.WriteLine($"PASS: {checks} fake-HTTP payment cases: 201 receipts, 200 replays, exact request identity, error/transport handling and invalid-response rejection.");
    }

    private static void Check(bool value, string message)
    {
        if (!value) throw new InvalidOperationException(message);
    }

    private sealed class FixtureHandler(HttpStatusCode status, string body, string contentType, Exception? failure)
        : HttpMessageHandler
    {
        public int Calls { get; private set; }
        public HttpMethod? Method { get; private set; }
        public string? Path { get; private set; }
        public string? Authorization { get; private set; }
        public string? Body { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++;
            Method = request.Method;
            Path = request.RequestUri!.AbsolutePath;
            Authorization = request.Headers.Authorization?.ToString();
            Body = await request.Content!.ReadAsStringAsync(ct);
            if (failure is not null) throw failure;
            return new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, contentType) };
        }
    }
}
