using System.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PersonalFinance.AdminMvc.ViewModels;

namespace PersonalFinance.AdminMvc.Controllers;

[Authorize(Policy = "Administrator")]
public sealed class ReportsController : Controller
{
    [HttpGet]
    public IActionResult Index([FromQuery] string? month)
    {
        var selectedMonth = month ?? DateTime.Today.ToString("yyyy-MM", CultureInfo.InvariantCulture);
        if (!DateOnly.TryParseExact(selectedMonth + "-01", "yyyy-MM-dd", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out _))
        {
            Response.StatusCode = StatusCodes.Status400BadRequest;
            return View(new MonthlyReportsViewModel(null, "Enter a valid month in YYYY-MM format."));
        }

        return View(new MonthlyReportsViewModel(selectedMonth));
    }
}
