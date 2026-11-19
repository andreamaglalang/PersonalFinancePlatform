using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Services;
using Backend = PersonalFinance.Infrastructure.Finance.Contracts;

var controller = typeof(PersonalFinance.Api.Controllers.ReportsController);
Require(controller.GetCustomAttributes(typeof(AuthorizeAttribute), true).Length > 0, "Reports must require authentication");
Require(((RouteAttribute)controller.GetCustomAttributes(typeof(RouteAttribute), true).Single()).Template == "api/reports", "Controller route");
Require(((HttpGetAttribute)controller.GetMethod("Monthly")!.GetCustomAttributes(typeof(HttpGetAttribute), true).Single()).Template == "monthly", "Action route");
var month = new DateOnly(2026, 11, 1);
var budget = new Backend.BudgetResponse(1, 2, "Food", true, month, 100m, 80, 25m, 75m, "OK", "PHP");
foreach (var populated in new[] { false, true })
{
    var payload = new Backend.MonthlyReportResponse(month, "PHP", populated ? 100m : 0m, populated ? 25m : 0m,
        populated ? 75m : 0m, populated ? 2 : 0, populated ? 5m : 0m,
        populated ? [new Backend.ReportCategoryTotal(2, "Food", 25m, 1)] : [],
        populated ? [new Backend.ReportCategoryTotal(1, "Salary", 100m, 1)] : [], populated ? [budget] : []);
    using var handler = new ContractHandler(payload);
    using var http = new HttpClient(handler) { BaseAddress = new("https://api.owli.example") };
    var result = await new ReportsApiClient(http).GetMonthlyAsync(month, "synthetic-contract-token", default);
    Require(result.Status == 200 && result.Report is not null, "Actual backend DTO must be accepted by MVC");
    Require(result.Report!.Budgets.Count == (populated ? 1 : 0), "Nested budget contract");
    Require(result.Report.SavingsContributed == (populated ? 5m : 0m), "Savings contract");
}
Console.WriteLine("PASS: authenticated reports route, empty/populated backend DTO serialization, MVC request and nested budget deserialization (offline).");
static void Require(bool value, string message) { if (!value) throw new Exception(message); }
sealed class ContractHandler(Backend.MonthlyReportResponse payload) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        if (request.Method != HttpMethod.Get || request.RequestUri!.PathAndQuery != "/api/reports/monthly?month=2026-11-01"
            || request.Headers.Authorization?.ToString() != "Bearer synthetic-contract-token") throw new Exception("MVC request mismatch");
        return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = JsonContent.Create(payload) });
    }
}
