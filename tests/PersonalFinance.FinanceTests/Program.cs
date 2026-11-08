using PersonalFinance.Infrastructure.Persistence;
using Microsoft.Extensions.DependencyInjection;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using PersonalFinance.FinanceTests;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;

int passed=0,failed=0;
void Assert(bool condition,string detail="Assertion failed") { if(!condition) throw new Exception(detail); }
async Task Check(string name,Func<FinanceFixture,Task> test) {
 await using var f=new FinanceFixture();
 try { await f.SeedAsync(); await test(f); passed++; Console.WriteLine("PASS "+name); }
 catch(Exception e) { failed++; Console.WriteLine("FAIL "+name+": "+e.Message); }
}
JsonElement Json(ApiReply r)=>JsonDocument.Parse(r.Body).RootElement;
CreateTransactionRequest Tx(int category=2,string type="Expense",decimal amount=10m,string currency="PHP",DateOnly? date=null)=>new() {
 ClientId=Guid.NewGuid(),CategoryId=category,Type=type,Amount=amount,CurrencyCode=currency,TransactionDate=date??new(2026,10,10),Description="Test"
};
async Task<ApiReply> Create(FinanceFixture f,CreateTransactionRequest? tx=null,int user=1)=>await f.SendAsync("POST","/api/transactions",tx??Tx(),f.Issue(user));
async Task<int> Created(FinanceFixture f,CreateTransactionRequest? tx=null) {var r=await Create(f,tx);Assert(r.Status==201,r.Body);return Json(r).GetProperty("transactionId").GetInt32();}
TransactionWriteRequest Edit(string currency="PHP",decimal amount=20m,DateOnly? date=null)=>new() {CategoryId=2,Type="Expense",Amount=amount,CurrencyCode=currency,TransactionDate=date??new(2026,10,10),Description="Edited"};
async Task<ApiReply> Update(FinanceFixture f,int id,TransactionWriteRequest dto,int user=1)=>await f.SendAsync("PUT",$"/api/transactions/{id}",dto,f.Issue(user));
await Check("Income creation",async f=>Assert((await Create(f,Tx(1,"Income"))).Status==201));
await Check("Expense creation and same-currency snapshot",async f=>{var r=await Create(f);Assert(r.Status==201);Assert(Json(r).GetProperty("fxRate").GetDecimal()==1m);});
foreach(var amount in new[]{0m,-1m,1.001m,10000000000000000m})
 await Check("Invalid source amount "+amount,async f=>Assert((await Create(f,Tx(amount:amount))).Status==400));
await Check("Invalid transaction type",async f=>Assert((await Create(f,Tx(type:"Other"))).Status==400));
foreach(var cat in new[]{1,3,5,999})
 await Check("Invalid category assignment "+cat,async f=>Assert((await Create(f,Tx(cat))).Status==400));
await Check("Unsupported currency",async f=>Assert((await Create(f,Tx(currency:"ZZZ"))).Status==400));
await Check("Invalid dates rejected by MVC",async f=>Assert((await f.SendAsync("POST","/api/transactions",new{clientId=Guid.NewGuid(),type="Expense",categoryId=2,amount=1,currencyCode="PHP",transactionDate="2026-02-30"},f.Issue(1))).Status==400));
await Check("Required fields and description limits",async f=>{Assert((await f.SendAsync("POST","/api/transactions",new{},f.Issue(1))).Status==400); var t=Tx();t.Description=new string('x',201);Assert((await Create(f,t)).Status==400);});
await Check("Ownership overposting rejected",async f=>Assert((await f.SendAsync("POST","/api/transactions",new{userId=2,clientId=Guid.NewGuid(),type="Expense",categoryId=2,amount=1,currencyCode="PHP",transactionDate="2026-10-10"},f.Issue(1))).Status==400));
await Check("Retrieval and safe DTO",async f=>{int id=await Created(f);var r=await f.SendAsync("GET",$"/api/transactions/{id}",bearer:f.Issue(1));Assert(r.Status==200 && !r.Body.Contains("userId",StringComparison.OrdinalIgnoreCase));});
await Check("Cross-user read update delete and administrator privacy",async f=>{int id=await Created(f);foreach(var user in new[]{2,3}) {Assert((await f.SendAsync("GET",$"/api/transactions/{id}",bearer:f.Issue(user))).Status==404);Assert((await Update(f,id,Edit(),user)).Status==404);Assert((await f.SendAsync("DELETE",$"/api/transactions/{id}",bearer:f.Issue(user))).Status==404);}});
await Check("Transaction update and ordinary deletion",async f=>{int id=await Created(f);Assert((await Update(f,id,Edit())).Status==200);var deletion=await f.SendAsync("DELETE",$"/api/transactions/{id}",bearer:f.Issue(1));Assert(deletion.Status==200 && Json(deletion).GetProperty("deleted").GetBoolean() && Json(deletion).GetProperty("transactionId").GetInt32()==id && Json(deletion).GetProperty("affectedBudgets").GetArrayLength()==0);Assert((await f.SendAsync("GET",$"/api/transactions/{id}",bearer:f.Issue(1))).Status==404);});
await Check("ClientId idempotency preserves original payload",async f=>{var t=Tx();var a=await Create(f,t);t.Amount=50;var b=await Create(f,t);Assert(b.Status==200 && Json(a).GetProperty("transactionId").GetInt32()==Json(b).GetProperty("transactionId").GetInt32() && Json(b).GetProperty("amount").GetDecimal()==10);});
await Check("ClientId namespace is per user",async f=>{var t=Tx();await Create(f,t);t.CategoryId=3;Assert((await Create(f,t,2)).Status==201);});
await Check("Filtering type category dates sorting pagination and owned history",async f=>{
 await Created(f);await Created(f,Tx(1,"Income",30));await Created(f,Tx(date:new(2026,9,1)));
 foreach(var q in new[]{"type=Income","categoryId=1","fromDate=2026-09-01&toDate=2026-09-30"}) {
 var r=await f.SendAsync("GET","/api/transactions?"+q,bearer:f.Issue(1));Assert(r.Status==200,r.Body);Assert(Json(r).GetProperty("totalCount").GetInt64()==1); }
 var p=await f.SendAsync("GET","/api/transactions?pageSize=1&sortBy=amount&sortDirection=desc",bearer:f.Issue(1));Assert(Json(p).GetProperty("items")[0].GetProperty("amount").GetDecimal()==30);
 var other=await f.SendAsync("GET","/api/transactions",bearer:f.Issue(2));Assert(Json(other).GetProperty("totalCount").GetInt64()==0);
});
await Check("Invalid filters and pagination",async f=>{foreach(var q in new[]{"page=0","pageSize=101","page=2147483647&pageSize=100","sortBy=invalid","fromDate=2026-10-11&toDate=2026-10-10","type=Other","categoryId=0"}) Assert((await f.SendAsync("GET","/api/transactions?"+q,bearer:f.Issue(1))).Status==400);});
await Check("All finance endpoint verbs require authentication",async f=>{foreach(var p in new[]{"/api/transactions","/api/categories"}) {foreach(var m in new[]{"GET","POST"}) Assert((await f.SendAsync(m,p, m=="POST"?new{}:null)).Status==401);foreach(var m in new[]{"GET","PUT","DELETE"}) Assert((await f.SendAsync(m,p+"/1",m=="PUT"?new{}:null)).Status==401);}});
await Check("Finance endpoints require HTTPS",async f=>Assert((await f.SendAsync("GET","/api/transactions",bearer:f.Issue(1),scheme:"http")).Status==400));
await Check("FX rounded before conversion",async f=>{var r=await Create(f,Tx(currency:"USD",amount:100000m));Assert(r.Status==201,r.Body);Assert(Json(r).GetProperty("fxRate").GetDecimal()==56.12345679m);Assert(Json(r).GetProperty("amountBase").GetDecimal()==5612345.68m);});
await Check("Stored snapshot preserved for description category and amount updates",async f=>{
 int id=await Created(f,Tx(currency:"USD"));int calls=f.Fx.Calls;f.Fx.Unavailable=true;
 var d=Edit("USD");d.CategoryId=4;var r=await Update(f,id,d);Assert(r.Status==200,r.Body);Assert(Json(r).GetProperty("fxRate").GetDecimal()==56.12345679m);Assert(Json(r).GetProperty("amountBase").GetDecimal()==1122.47m);Assert(f.Fx.Calls==calls);
 d.Description="Again";Assert((await Update(f,id,d)).Status==200 && f.Fx.Calls==calls);
});
await Check("Date change obtains historical snapshot",async f=>{int id=await Created(f,Tx(currency:"USD"));f.Fx.Rate=57;var r=await Update(f,id,Edit("USD",date:new(2026,10,20)));Assert(r.Status==200,r.Body);Assert(Json(r).GetProperty("fxRate").GetDecimal()==57 && f.Fx.Calls==2);});
await Check("Currency change obtains validated new snapshot",async f=>{int id=await Created(f);var r=await Update(f,id,Edit("EUR"));Assert(r.Status==200 && f.Fx.Calls==1);});
await Check("Unavailable historical rate rejects update without partial mutation",async f=>{int id=await Created(f);f.Fx.Unavailable=true;Assert((await Update(f,id,Edit("USD"))).Status==503);await f.DbAsync(async db=>{var t=await db.Transactions.SingleAsync();Assert(t.CurrencyCode=="PHP"&&t.Amount==10);});});
await Check("Valid cached rate works during provider outage",async f=>{await Created(f,Tx(currency:"USD"));f.Fx.Unavailable=true;Assert((await Create(f,Tx(currency:"USD",date:new(2026,10,17)))).Status==201 && f.Fx.Calls==1);});
await Check("Stale cached rate is not silently reused",async f=>{await Created(f,Tx(currency:"USD"));f.Fx.Unavailable=true;Assert((await Create(f,Tx(currency:"USD",date:new(2026,10,18)))).Status==503);});
foreach(var age in new[]{-1,8})
 await Check("Reject future or stale provider rate "+age,async f=>{f.Fx.Age=age;Assert((await Create(f,Tx(currency:"USD"))).Status==503);});
foreach(var rate in new[]{0m,-1m,0.000000004m,10000000000m})
 await Check("Reject invalid FX rate "+rate,async f=>{f.Fx.Rate=rate;Assert((await Create(f,Tx(currency:"USD"))).Status==503);});
await Check("Conversion rounding boundaries and overflow",f=>{
 Assert(FinanceValidation.NormalizeRate(1.000000005m)==1.00000001m);
 Assert(FinanceValidation.ConvertAmount(1m,1.005m)==1.01m);
 Assert(FinanceValidation.ConvertAmount(0.01m,0.00000001m)==0m);
 foreach(var rate in new[]{2m,FinanceValidation.MaximumRate}) {bool rejected=false;try{FinanceValidation.ConvertAmount(FinanceValidation.MaximumMoney,rate);}catch(FinanceException){rejected=true;}Assert(rejected);}
 return Task.CompletedTask;});
object Cat(string type="Expense",bool? active=null,string name="Renamed")=>new{name,type,isActive=active,colorHEX="#AABBCC"};
await Check("Category listing excludes others and archived by default",async f=>{var r=await f.SendAsync("GET","/api/categories",bearer:f.Issue(1));Assert(r.Status==200 && Json(r).GetProperty("totalCount").GetInt64()==3);});
await Check("Category create update delete and duplicate names allowed",async f=>{for(int n=0;n<2;n++){var r=await f.SendAsync("POST","/api/categories",Cat(),f.Issue(1));Assert(r.Status==201,r.Body);int id=Json(r).GetProperty("categoryId").GetInt32();Assert((await f.SendAsync("PUT",$"/api/categories/{id}",Cat("Income"),f.Issue(1))).Status==200);Assert((await f.SendAsync("DELETE",$"/api/categories/{id}",bearer:f.Issue(1))).Status==204);}});
await Check("Protected system categories and administrator-only management",async f=>{Assert((await f.SendAsync("PUT","/api/categories/4",Cat(),f.Issue(1))).Status==403);Assert((await f.SendAsync("PUT","/api/categories/defaults/4",Cat(),f.Issue(1))).Status==403);Assert((await f.SendAsync("PUT","/api/categories/defaults/4",Cat(),f.Issue(3))).Status==200);Assert((await f.SendAsync("PUT","/api/categories/3",Cat(),f.Issue(3))).Status==404);});
await Check("Referenced category type and deletion conflict; archive preserves history",async f=>{await Created(f);Assert((await f.SendAsync("PUT","/api/categories/2",Cat("Income"),f.Issue(1))).Status==409);Assert((await f.SendAsync("DELETE","/api/categories/2",bearer:f.Issue(1))).Status==409);Assert((await f.SendAsync("PUT","/api/categories/2",Cat(active:false),f.Issue(1))).Status==200);await f.DbAsync(async db=>Assert(await db.Transactions.CountAsync()==1));});
foreach(var reference in new[]{"budget","bill"})
 await Check("Type change blocked by "+reference,async f=>{await f.DbAsync(async db=>{if(reference=="budget") db.Budgets.Add(new(){UserId=1,CategoryId=2,LimitAmount=1,AlertThresholdPct=80,PeriodMonth=new(2026,10,1)});else db.RecurringBills.Add(new(){UserId=1,CategoryId=2,Name="Bill",CurrencyCode="PHP",Amount=1,AnchorDate=new(2026,10,10),NextDueDate=new(2026,10,10),IntendedDayOfMonth=10});await db.SaveChangesAsync();});Assert((await f.SendAsync("PUT","/api/categories/2",Cat("Income"),f.Issue(1))).Status==409);});
await Check("Bill payments cannot be deleted",async f=>{int id=await Created(f);await f.DbAsync(async db=>{var row=await db.Transactions.SingleAsync();row.BillId=1;row.BillDueDate=new(2026,10,10);await db.SaveChangesAsync();});Assert((await f.SendAsync("DELETE",$"/api/transactions/{id}",bearer:f.Issue(1))).Status==409);});
await Check("Concurrent request idempotency under serialized test write scope",async f=>{
 var t=Tx();var responses=await Task.WhenAll(Create(f,t),Create(f,t));Assert(responses.All(r=>r.Status is 200 or 201));
 await f.DbAsync(async db=>Assert(await db.Transactions.CountAsync()==1));
});
await Check("Concurrent edit detected before mutation",async f=>{
 int id=await Created(f);
 var scope=(MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
 scope.BeforeWrite=()=>f.DbAsync(async db=>{var row=await db.Transactions.SingleAsync();row.Description="Concurrent";await db.SaveChangesAsync();});
 Assert((await Update(f,id,Edit())).Status==409);
 await f.DbAsync(async db=>Assert((await db.Transactions.SingleAsync()).Description=="Concurrent"));
});
await Check("Category archive race revalidated within write scope",async f=>{
 var scope=(MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
 scope.BeforeWrite=()=>f.DbAsync(async db=>{(await db.Categories.SingleAsync(c=>c.CategoryId==2)).IsActive=false;await db.SaveChangesAsync();});
 Assert((await Create(f)).Status==400);
 await f.DbAsync(async db=>Assert(!await db.Transactions.AnyAsync()));
});
await Check("Frankfurter historical URL and currency catalog cache",async f=>{
 var handler=new FakeHttp(req=>{
 var path=req.RequestUri!.PathAndQuery;
 var json=path.EndsWith("/currencies") ? "[{\"iso_code\":\"USD\"},{\"iso_code\":\"PHP\"}]" : "{\"base\":\"USD\",\"quote\":\"PHP\",\"date\":\"2026-10-09\",\"rate\":56.1}";
 if(!path.EndsWith("/currencies")) Assert(path=="/v2/rate/USD/PHP?date=2026-10-10",path);
 return new(System.Net.HttpStatusCode.OK){Content=new StringContent(json,System.Text.Encoding.UTF8,"application/json")};
 });
 using var client=new FrankfurterClient(new HttpClient(handler){BaseAddress=new("https://api.frankfurter.dev/v2/")},TimeProvider.System);
 Assert(await client.SupportsAsync("USD",default));Assert(await client.SupportsAsync("PHP",default));
 var rate=await client.GetRateAsync("USD","PHP",new(2026,10,10),default);Assert(rate.Rate==56.1m && handler.Calls==2);
});
await Check("Frankfurter transient retries capped and permanent failures not retried",async f=>{
 foreach(var status in new[]{System.Net.HttpStatusCode.InternalServerError,System.Net.HttpStatusCode.NotFound}) {
 var handler=new FakeHttp(_=>new(status));using var client=new FrankfurterClient(new HttpClient(handler){BaseAddress=new("https://api.frankfurter.dev/v2/")},TimeProvider.System);
 bool rejected=false;try{await client.GetRateAsync("USD","PHP",new(2026,10,10),default);}catch(FinanceException){rejected=true;}
 Assert(rejected && handler.Calls==(status==System.Net.HttpStatusCode.NotFound?1:4));
 }});
await Check("Frankfurter wrong currency pair and malformed JSON rejected",async f=>{
 foreach(var json in new[]{"{\"base\":\"EUR\",\"quote\":\"PHP\",\"date\":\"2026-10-10\",\"rate\":56}","not json"}) {
 using var client=new FrankfurterClient(new HttpClient(new FakeHttp(_=>new(System.Net.HttpStatusCode.OK){Content=new StringContent(json,System.Text.Encoding.UTF8,"application/json")})){BaseAddress=new("https://api.frankfurter.dev/v2/")},TimeProvider.System);
 bool rejected=false;try{await client.GetRateAsync("USD","PHP",new(2026,10,10),default);}catch(FinanceException){rejected=true;}Assert(rejected);
 }});
await Check("Archived historical category remains editable without new assignments",async f=>{
 int id=await Created(f);
 Assert((await f.SendAsync("PUT","/api/categories/2",Cat(active:false),f.Issue(1))).Status==200);
 Assert((await Update(f,id,Edit())).Status==200);
 Assert((await Create(f)).Status==400);
});
await Check("Omitted archive status preserves existing category state",async f=>{
 var r=await f.SendAsync("PUT","/api/categories/5",new{name="Still archived",type="Expense"},f.Issue(1));
 Assert(r.Status==200 && !Json(r).GetProperty("isActive").GetBoolean());
});
await Check("Category validation and cross-user delete",async f=>{
 foreach(var body in new[]{Cat(name:" "),Cat(name:new string('x',51)),Cat(type:"Other")})
 Assert((await f.SendAsync("POST","/api/categories",body,f.Issue(1))).Status==400);
 Assert((await f.SendAsync("DELETE","/api/categories/3",bearer:f.Issue(1))).Status==404);
});
await Check("Category type rechecks references inside serialized operation",async f=>{
 var scope=(MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
 scope.BeforeWrite=()=>f.DbAsync(async db=>{db.Budgets.Add(new(){UserId=1,CategoryId=2,LimitAmount=1,AlertThresholdPct=80,PeriodMonth=new(2026,10,1)});await db.SaveChangesAsync();});
 Assert((await f.SendAsync("PUT","/api/categories/2",Cat("Income"),f.Issue(1))).Status==409);
});
await Check("Provider requires HTTP 200 and valid catalog records",async f=>{
 foreach(var response in new[]{new HttpResponseMessage(System.Net.HttpStatusCode.Created),new HttpResponseMessage(System.Net.HttpStatusCode.OK){Content=new StringContent("[null]",System.Text.Encoding.UTF8,"application/json")}}) {
 using var client=new FrankfurterClient(new HttpClient(new FakeHttp(_=>response)){BaseAddress=new("https://api.frankfurter.dev/v2/")},TimeProvider.System);
 bool rejected=false;try{await client.SupportsAsync("USD",default);}catch(FinanceException){rejected=true;}Assert(rejected);
 }});
await Check("Fixed boundary trim preserves interior characters and unlisted zero-width space",f=>{
 var chars=new[]{9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288};
 foreach(var ch in chars) Assert(CategoryNameNormalization.Trim(((char)ch)+"Food"+((char)ch))=="Food");
 Assert(CategoryNameNormalization.Trim(" A  B\tC\u00A0D ")=="A  B\tC\u00A0D");
 Assert(CategoryNameNormalization.Trim("\u200BFood\u200B")=="\u200BFood\u200B");
 return Task.CompletedTask;
});
await Check("All boundary-only category names rejected before persistence",async f=>{
 foreach(var ch in new[]{9,32,133,160,5760,8192,8232,8239,8287,12288})
 Assert((await f.SendAsync("POST","/api/categories",Cat(name:new string((char)ch,3)),f.Issue(1))).Status==400);
});
await Check("Exact-name duplicate rejects private create including inactive categories",async f=>{
 foreach(var name in new[]{"Food","Archived","\t\u00A0Food\u3000"})
 Assert((await f.SendAsync("POST","/api/categories",Cat(name:name),f.Issue(1))).Status==409);
});
await Check("System-default duplicates reject and do not collide with private owner scope",async f=>{
 Assert((await f.SendAsync("POST","/api/categories/defaults",Cat(name:"Default"),f.Issue(3))).Status==409);
 Assert((await f.SendAsync("POST","/api/categories",Cat(name:"Default"),f.Issue(1))).Status==201);
 Assert((await f.SendAsync("POST","/api/categories",Cat(name:"Food"),f.Issue(2))).Status==201);
 Assert((await f.SendAsync("POST","/api/categories",Cat(type:"Income",name:"Food"),f.Issue(1))).Status==201);
});
await Check("Self rename permitted while conflicting rename rejected",async f=>{
 Assert((await f.SendAsync("PUT","/api/categories/2",Cat(name:"Food"),f.Issue(1))).Status==200);
 Assert((await f.SendAsync("PUT","/api/categories/5",Cat(name:"Food"),f.Issue(1))).Status==409);
});
await Check("Renaming category preserves transaction identity and relationships",async f=>{
 var id=await Created(f);
 Assert((await f.SendAsync("PUT","/api/categories/2",Cat(name:"New name"),f.Issue(1))).Status==200);
 var response=await f.SendAsync("GET",$"/api/transactions/{id}",bearer:f.Issue(1));
 Assert(response.Status==200 && Json(response).GetProperty("categoryId").GetInt32()==2 && Json(response).GetProperty("categoryName").GetString()=="New name");
});
foreach(var error in new[]{2601,2627})
 foreach(var method in new[]{"POST","PUT"})
 await Check($"Synthetic SQL {error} on category {method} returns safe HTTP 409",async f=>{
 var scope=(MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
 scope.BeforeWrite=()=>throw new DbUpdateException("SYNTHETIC_PRIVATE_SQL_DETAIL",SyntheticSqlError.Create(error));
 var r=await f.SendAsync(method,method=="POST"?"/api/categories":"/api/categories/2",Cat(),f.Issue(1));
 Assert(r.Status==409,r.Body);
 Assert(!r.Body.Contains("SYNTHETIC_PRIVATE_SQL_DETAIL") && !r.Body.Contains("UX_Categories"));
 });
// Linkage fixtures exercise service protection, not SQL constraints or payment acceptance.
async Task LinkPayment(FinanceFixture f, int id, string mode = "both") => await f.DbAsync(async db =>
{
    var row = await db.Transactions.SingleAsync(t => t.TransactionId == id);
    row.BillId = mode == "date-only" ? null : 1;
    row.BillDueDate = mode == "id-only" ? null : new DateOnly(2026, 10, 10);
    await db.SaveChangesAsync();
});
async Task<string> ProtectionState(FinanceFixture f)
{
    string result = "";
    await f.DbAsync(async db => result = JsonSerializer.Serialize(new
    {
        Transactions = await db.Transactions.AsNoTracking().OrderBy(t => t.TransactionId).ToListAsync(),
        Budgets = await db.Budgets.AsNoTracking().OrderBy(b => b.BudgetId).ToListAsync()
    }));
    return result;
}
void PaymentConflict(ApiReply reply)
{
    Assert(reply.Status == 409, reply.Body);
    Assert(Json(reply).GetProperty("title").GetString() == "Bill-payment transactions cannot be modified or deleted.", reply.Body);
}
foreach (var change in new[] { "amount", "category", "currency", "date", "description", "type", "unchanged" })
    await Check("Protected payment rejects " + change + " update without financial, budget or FX side effects", async f =>
    {
        Assert((await f.SendAsync("POST", "/api/budgets", new { categoryId = 2, periodMonth = "2026-10-01", limitAmount = 100m, alertThresholdPct = 80 }, f.Issue(1))).Status == 201);
        var id = await Created(f); await LinkPayment(f, id);
        // Recalculation at OK would clear this marker, making an accidental call observable.
        await f.DbAsync(async db => { (await db.Budgets.SingleAsync()).LastAlertStatus = PersonalFinance.Domain.Enums.BudgetAlertStatus.Warning; await db.SaveChangesAsync(); });
        var before = await ProtectionState(f); var calls = f.Fx.Calls; var saves = f.RowVersions.SaveAttempts;
        var dto = Edit(amount: 10m); dto.Description = "Test";
        switch (change)
        {
            case "amount": dto.Amount = 20m; break;
            case "category": dto.CategoryId = 4; break;
            case "currency": dto.CurrencyCode = "USD"; break;
            case "date": dto.TransactionDate = new(2026, 10, 11); break;
            case "description": dto.Description = "Changed"; break;
            case "type": dto.Type = "Income"; dto.CategoryId = 1; break;
        }
        PaymentConflict(await Update(f, id, dto));
        Assert(await ProtectionState(f) == before, "Rejected update changed financial state.");
        Assert(f.Fx.Calls == calls && f.RowVersions.SaveAttempts == saves, "Rejected update performed FX or SaveChanges.");
    });
foreach (var mode in new[] { "both", "id-only", "date-only" })
    foreach (var verb in new[] { "PUT", "DELETE" })
        await Check($"Protected payment rejects {verb} with {mode} linkage", async f =>
        {
            var id = await Created(f); await LinkPayment(f, id, mode);
            var before = await ProtectionState(f); var saves = f.RowVersions.SaveAttempts;
            PaymentConflict(await f.SendAsync(verb, $"/api/transactions/{id}", verb == "PUT" ? Edit() : null, f.Issue(1)));
            Assert(await ProtectionState(f) == before && f.RowVersions.SaveAttempts == saves);
        });
foreach (var user in new[] { 2, 3 })
    await Check("Protected payment remains private from user " + user, async f =>
    {
        var id = await Created(f); await LinkPayment(f, id); var before = await ProtectionState(f);
        foreach (var verb in new[] { "GET", "PUT", "DELETE" })
            Assert((await f.SendAsync(verb, $"/api/transactions/{id}", verb == "PUT" ? Edit() : null, f.Issue(user))).Status == 404);
        Assert(await ProtectionState(f) == before);
    });
await Check("Protected payment requires authentication", async f =>
{
    var id = await Created(f); await LinkPayment(f, id);
    foreach (var verb in new[] { "PUT", "DELETE" })
        Assert((await f.SendAsync(verb, $"/api/transactions/{id}", verb == "PUT" ? Edit() : null)).Status == 401);
});
foreach (var verb in new[] { "POST", "PUT" })
    await Check("Bill-linkage overposting rejected on " + verb, async f =>
    {
        var id = await Created(f); var before = await ProtectionState(f);
        foreach (var link in new[] { "billId", "billDueDate" })
        {
            var body = new Dictionary<string, object?> { ["type"] = "Expense", ["categoryId"] = 2,
                ["amount"] = 10m, ["currencyCode"] = "PHP", ["transactionDate"] = "2026-10-10" };
            if (verb == "POST") body["clientId"] = Guid.NewGuid();
            body[link] = link == "billId" ? (object)1 : "2026-10-10";
            Assert((await f.SendAsync(verb, verb == "POST" ? "/api/transactions" : $"/api/transactions/{id}", body, f.Issue(1))).Status == 400);
        }
        Assert(await ProtectionState(f) == before);
    });
foreach (var verb in new[] { "PUT", "DELETE" })
    foreach (var mode in new[] { "both", "id-only", "date-only" })
        await Check($"Linkage appearing between reads rejects {verb}: {mode}", async f =>
        {
            var id = await Created(f); string linked = "";
            var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
            scope.BeforeWrite = async () => { await LinkPayment(f, id, mode); linked = await ProtectionState(f); };
            PaymentConflict(await f.SendAsync(verb, $"/api/transactions/{id}", verb == "PUT" ? Edit() : null, f.Issue(1)));
            Assert(linked.Length > 0 && await ProtectionState(f) == linked);
        });
foreach (var verb in new[] { "PUT", "DELETE" })
    await Check("Deletion between reads retains 404 for " + verb, async f =>
    {
        var id = await Created(f);
        var scope = (MemoryWriteScope)f.Services.GetRequiredService<IFinancialWriteScope>();
        scope.BeforeWrite = () => f.DbAsync(async db => { db.Transactions.Remove(await db.Transactions.SingleAsync()); await db.SaveChangesAsync(); });
        Assert((await f.SendAsync(verb, $"/api/transactions/{id}", verb == "PUT" ? Edit() : null, f.Issue(1))).Status == 404);
        await f.DbAsync(async db => Assert(!await db.Transactions.AnyAsync()));
    });
// Existing ordinary update/delete and ClientId replay checks above remain unchanged.
await BudgetTests.RunAsync(Check);
await SavingsGoalTests.RunAsync(Check);
await BillPaymentTests.RunAsync(Check);
Console.WriteLine($"Finance checks: {passed} passed, {failed} failed. In-memory EF + actual JWT/MVC; SQL Server locking/constraints NOT executed.");
return failed==0?0:1;
