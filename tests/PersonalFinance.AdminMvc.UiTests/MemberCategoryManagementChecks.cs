using System.Net;
using System.Text;
using Microsoft.AspNetCore.Authorization;
using PersonalFinance.AdminMvc.Areas.Member.Controllers;
using PersonalFinance.AdminMvc.Services;
using PersonalFinance.AdminMvc.Services.Contracts;

internal static class MemberCategoryManagementChecks
{
    public static async Task RunAsync()
    {
        Check(typeof(CategoriesController).GetCustomAttributes(typeof(AuthorizeAttribute), true)
            .Cast<AuthorizeAttribute>().Any(attribute => attribute.Policy == "User"),
            "Member categories require the authenticated User policy.");
        foreach (var action in typeof(CategoriesController).GetMethods().Where(method => method.IsDefined(typeof(Microsoft.AspNetCore.Mvc.HttpPostAttribute), inherit: true)))
            Check(action.IsDefined(typeof(Microsoft.AspNetCore.Mvc.ValidateAntiForgeryTokenAttribute), inherit: true),
                $"{action.Name} must validate antiforgery.");
        var controllerSource = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "CategoriesController.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Categories", "Index.cshtml"));
        Check(controllerSource.Contains("IsSystemDefault") && controllerSource.Contains("CreateMemberAsync")
            && controllerSource.Contains("UpdateMemberAsync") && controllerSource.Contains("DeleteMemberAsync"),
            "Member actions must use personal category endpoints and protect shared defaults.");
        Check(view.Contains("Personal") && view.Contains("OWLI shared") && view.Contains("Inactive"),
            "The category page distinguishes personal, shared and inactive categories.");

        using var handler = new CategoryHandler();
        using var http = new HttpClient(handler) { BaseAddress = new Uri("https://api.example.test/") };
        var api = new CategoryApiClient(http);
        Check((await api.CreateMemberAsync(new CategoryWriteRequest { Name = "Travel", Type = "Expense", ColorHEX = "#AE81F8", IsActive = true }, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/categories" && handler.LastMethod == HttpMethod.Post,
            "Personal categories are created through POST /api/categories, not the admin defaults route.");
        Check((await api.UpdateMemberAsync(8, new CategoryWriteRequest { Name = "Travel", Type = "Expense", ColorHEX = "#AE81F8", IsActive = false }, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/categories/8" && handler.LastMethod == HttpMethod.Put,
            "Personal category edits and activation state use the owner-scoped API route.");
        Check((await api.DeleteMemberAsync(8, "fixture-token", default)).Succeeded
            && handler.LastPath == "/api/categories/8" && handler.LastMethod == HttpMethod.Delete,
            "Personal category deletion uses the API safeguards and expects 204.");
        Check(handler.LastAuthorization == "Bearer fixture-token", "Category requests use the member's bearer token.");
        Console.WriteLine("PASS: Member category authorization, shared-category protection, antiforgery and personal API routes.");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private sealed class CategoryHandler : HttpMessageHandler
    {
        public HttpMethod? LastMethod { get; private set; }
        public string? LastPath { get; private set; }
        public string? LastAuthorization { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            LastMethod = request.Method;
            LastPath = request.RequestUri!.PathAndQuery;
            LastAuthorization = request.Headers.Authorization?.ToString();
            if (request.Method == HttpMethod.Delete)
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NoContent));
            const string body = """{"categoryId":8,"name":"Travel","type":"Expense","colorHEX":"#AE81F8","isActive":true,"isSystemDefault":false}""";
            return Task.FromResult(new HttpResponseMessage(request.Method == HttpMethod.Post ? HttpStatusCode.Created : HttpStatusCode.OK)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            });
        }
    }
}
