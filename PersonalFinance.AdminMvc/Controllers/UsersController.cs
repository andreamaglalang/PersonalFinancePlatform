using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.Authentication;
using PersonalFinance.AdminMvc.Services;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public sealed class UsersController(AdminApiClient api) : Controller
{
    [HttpGet]
    public async Task<IActionResult> Index([FromQuery] string? search, [FromQuery] int page, [FromQuery] string? status, CancellationToken ct)
    {
        search = search?.Trim() ?? string.Empty;
        status ??= string.Empty;
        if (!ModelState.IsValid || search.Length > 100 || page < 0 || status is not ("" or "active" or "locked"))
        {
            Response.StatusCode = 400;
            return View(new RegisteredUsersViewModel(search, 1, Error: "Enter up to 100 characters and a valid page number."));
        }

        page = Math.Max(1, page);
        var result = await api.GetUsersAsync(User, search, page, status, ct);
        if (result.Data is { Items: not null, Page: > 0, PageSize: > 0, TotalCount: >= 0 } users)
            return View(new RegisteredUsersViewModel(search, users.Page, users, Status: status));
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();
        Response.StatusCode = result.Status is 400 or 429 ? result.Status : 503;
        var message = result.Status == 429 ? "Too many requests. Wait a moment and refresh."
            : "Registered users are temporarily unavailable. Try refreshing.";
        return View(new RegisteredUsersViewModel(search, page, Error: message, Status: status));
    }

    [HttpGet]
    public async Task<IActionResult> Details(int id, CancellationToken ct)
    {
        if (!ModelState.IsValid || id < 1) return NotFound();
        var result = await api.GetUserAsync(User, id, ct);
        if (result.Status == 401)
        {
            await HttpContext.SignOutAsync(AdminSessionStore.Scheme);
            return RedirectToAction("Login", "Account");
        }
        if (result.Status == 403) return Forbid();
        if (result.Data is null)
            Response.StatusCode = result.Status is 404 or 429 ? result.Status : 503;
        return View(result);
    }

}

public sealed record RegisteredUsersViewModel(string Search, int Page, RegisteredUsersPage? Users = null, string? Error = null, string Status = "");
