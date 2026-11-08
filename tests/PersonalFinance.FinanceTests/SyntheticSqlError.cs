using System.Reflection;
using Microsoft.Data.SqlClient;
namespace PersonalFinance.FinanceTests;

/// <summary>Constructs synthetic provider errors without a connection; never contains real database data.</summary>
internal static class SyntheticSqlError
{
    public static SqlException Create(int number)
    {
        var errors = (SqlErrorCollection)Activator.CreateInstance(typeof(SqlErrorCollection), nonPublic: true)!;
        var constructor = typeof(SqlError).GetConstructors(BindingFlags.Instance | BindingFlags.NonPublic)
            .OrderBy(c => c.GetParameters().Length).First();
        var args = constructor.GetParameters().Select((p,i) =>
            p.ParameterType == typeof(string) ? (object)"SYNTHETIC_PRIVATE_SQL_DETAIL" :
            p.ParameterType == typeof(int) ? (i==0 ? number : 0) :
            p.ParameterType.IsValueType ? Activator.CreateInstance(p.ParameterType) : null).ToArray();
        var error = (SqlError)constructor.Invoke(args);
        typeof(SqlErrorCollection).GetMethod("Add", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(errors,[error]);
        var factory = typeof(SqlException).GetMethods(BindingFlags.Static | BindingFlags.NonPublic)
            .First(m => m.Name=="CreateException" && m.GetParameters().Length==2
                && m.GetParameters()[0].ParameterType==typeof(SqlErrorCollection));
        return (SqlException)factory.Invoke(null,[errors,"16.0"])!;
    }
}
