using PersonalFinance.AdminMvc.Services;
internal static class PlanBudgetSummaryChecks
{
    public static void Run()
    {
        var month = new DateOnly(2024,2,1);
        BudgetResponse Budget(int id,decimal limit,decimal spent,string currency="PHP") => new(id,id,"Category "+id,true,month,limit,80,spent,limit-spent,spent>limit?"Exceeded":spent>=limit*.8m?"Warning":"OK",currency);
        var all = Enumerable.Range(1,21).Select(id=>Budget(id,100,50)).ToArray();
        var summary=BudgetPlanSummary.Calculate(all,month,new(2024,2,28))!;
        Check(summary.Allocated==2100 && summary.Spent==1050 && summary.Remaining==1050 && summary.DaysLeft==2 && summary.DailyAllowance==525,"Complete data beyond one page aggregates exactly, including leap-day allowance.");
        var over=BudgetPlanSummary.Calculate([Budget(1,100,150)],month,new(2024,2,29))!;
        Check(over.Remaining == -50 && over.Utilization == 150 && over.Fill == 100 && over.DailyAllowance == 0,"Overspending remains negative financially; only drawing and Android daily allowance are capped.");
        Check(BudgetPlanSummary.Calculate([],month,new(2024,2,1)) is null && BudgetPlanSummary.Calculate([Budget(1,100,0),Budget(2,100,0,"USD")],month,new(2024,2,1)) is null,"Empty and mixed-currency budget sets never invent totals.");
        Check(BudgetPlanSummary.Calculate([Budget(1,100,0)],month,new(2024,3,1))!.DailyAllowance is null && BudgetPlanSummary.Calculate([Budget(1,100,0)],month,new(2024,1,1))!.DaysLeft is null,"Past and future months have no current-month daily allowance.");
        Check(Math.Abs(BudgetPlanSummary.Percentage(2422,2700)-89.7037037m)<.000001m && Math.Abs(BudgetPlanSummary.Percentage(4538,6000)-75.6333333m)<.000001m && BudgetPlanSummary.Percentage(1250,2000)==62.5m && BudgetPlanSummary.Percentage(0,0)==0,"Budget progress accurately distinguishes all requested examples and protects zero division.");
        Console.WriteLine("PASS: Plan summary complete-set totals, leap year, negative remaining, daily allowance, empty/currency guards and progress calculations.");
    }
    private static void Check(bool value,string message) {if(!value) throw new InvalidOperationException(message);}
}
