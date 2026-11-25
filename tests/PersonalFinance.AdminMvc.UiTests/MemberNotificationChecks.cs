internal static class MemberNotificationChecks
{
    public static void Run()
    {
        var client = File.ReadAllText(MvcProject.FindFile("Services", "NotificationsApiClient.cs"));
        var controller = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Controllers", "NotificationsController.cs"));
        var view = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Notifications", "Index.cshtml"));
        var layout = File.ReadAllText(MvcProject.FindFile("Areas", "Member", "Views", "Shared", "_MemberLayout.cshtml"));

        if (controller.Contains("AuthorizationConfiguration.UserPolicy") is false
            || controller.Contains("AdministratorPolicy"))
            throw new InvalidOperationException("The notification inbox must be Member-scoped and never administrator-scoped.");
        if (controller.Contains("MemberSessionGate.AcquireAsync") is false
            || controller.Contains("MemberSessionGate.RejectAsync") is false)
            throw new InvalidOperationException("The inbox must resolve and reject through MemberSessionGate.");

        // Read-only: no mark-as-read endpoint is invented, and no HTTP method other than GET is used.
        if (client.Contains("HttpMethod.Post") || client.Contains("HttpMethod.Put")
            || client.Contains("HttpMethod.Delete") || client.Contains("mark-as-read")
            || client.Contains("MarkAsRead"))
            throw new InvalidOperationException("The notification client must stay read-only (GET only).");
        if (client.Contains("api/notifications") is false || client.Contains("UnreadOnly=") is false
            || client.Contains("Page=") is false)
            throw new InvalidOperationException("The client must use the existing api/notifications contract.");
        if (client.Contains("RespectRequiredConstructorParameters = true") is false)
            throw new InvalidOperationException("The notification contract must mirror the API DTOs exactly.");

        // Newest-first ordering is the API's job; the page must show an accessible summary, not a bare number.
        if (view.Contains("role=\"status\"") is false)
            throw new InvalidOperationException("The inbox must announce its result through a status region.");
        if (view.Contains("Model.Summary") is false)
            throw new InvalidOperationException("The inbox must show an accessible 'n of m notifications' summary.");
        if (view.Contains("member-empty") is false || view.Contains("Loading your notifications") is false)
            throw new InvalidOperationException("The inbox must define empty and loading states.");
        if (view.Contains("name=\"UnreadOnly\"") is false)
            throw new InvalidOperationException("The unread-only filter must be a real form control.");
        if (view.Contains("aria-label=\"Notification pages\"") is false)
            throw new InvalidOperationException("Server-side pagination must be an accessible navigation region.");
        if (view.Contains("\"BudgetAlert\" => \"Budget alert\"") is false
            || view.Contains("\"MonthlySummary\" => \"Monthly summary\"") is false
            || view.Contains("ConvertTimeFromUtc") is false || view.Contains("member-notification-refresh") is false)
            throw new InvalidOperationException("Notification types must be human-readable, timestamps localized and refresh available.");
        if (view.Contains("@notification.Type</span>") || view.Contains("Ref @referenceId"))
            throw new InvalidOperationException("Raw notification enum values and internal references must not be prominent user-facing labels.");

        // Inbox remains reachable from a labelled header link and desktop secondary navigation.
        if (layout.Contains("asp-controller=\"Notifications\"") is false)
            throw new InvalidOperationException("The member topbar must link to the notification inbox.");
        if (layout.Contains("data-nav=\"notifications\""))
            throw new InvalidOperationException("Notifications must not add a seventh bottom-nav destination.");
        if (layout.Split("data-nav=").Length - 1 != 5)
            throw new InvalidOperationException("The Member primary navigation must also expose Account as its fifth destination.");

        Console.WriteLine("PASS: the member notification inbox is read-only, member-scoped, filtered, paged and accessible.");
    }
}
