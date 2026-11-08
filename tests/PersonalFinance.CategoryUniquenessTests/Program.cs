using Microsoft.EntityFrameworkCore;
using PersonalFinance.Domain.Entities;
using PersonalFinance.Domain.Enums;
using PersonalFinance.FinanceTests;
using PersonalFinance.Infrastructure.Authentication;
using PersonalFinance.Infrastructure.Finance;
using PersonalFinance.Infrastructure.Finance.Contracts;
using PersonalFinance.Infrastructure.Persistence;

int passed=0,failed=0;
void Assert(bool value){if(!value)throw new Exception("Assertion failed");}
async Task Check(string name,Func<Task> action){
 try{await action();passed++;Console.WriteLine("PASS "+name);}
 catch(Exception e){failed++;Console.WriteLine("FAIL "+name+": "+e.GetType().Name+": "+e.Message);}
}
CategoryWriteRequest Request(string name,string type="Expense",bool? active=null)=>new(){Name=name,Type=type,IsActive=active};
await Check("Exact 25-character boundary set and interior preservation",()=>{
 var characters=new[]{9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288};
 foreach(var ch in characters) Assert(CategoryNameNormalization.Trim(((char)ch)+"Food"+((char)ch))=="Food");
 Assert(CategoryNameNormalization.Trim(" A  B\tC\u00A0D ")=="A  B\tC\u00A0D");
 Assert(CategoryNameNormalization.Trim("\u200BFood\u200B")=="\u200BFood\u200B");
 return Task.CompletedTask;
});
await Check("Authoritative duplicate query translates to shadow column with parameter and nullable scope",()=>{
 using var db=new ApplicationDbContext(new DbContextOptionsBuilder<ApplicationDbContext>().UseSqlServer().Options);
 string name="Food";int? userId=null;
 var sql=db.Categories.Where(c=>c.UserId==userId && c.Type==TransactionType.Expense
     && EF.Property<string>(c,CategoryNameNormalization.PropertyName)==name).ToQueryString();
 Assert(sql.Contains("[NormalizedName] =") && sql.Contains("[UserId] IS NULL") && !sql.Contains("UPPER(") && !sql.Contains("LTRIM("));
 return Task.CompletedTask;
});
await Check("Create trims before storing, preserves interior spaces, assigns claim owner",async()=>{
 await using var f=new Fixture();var result=await f.Service.CreateAsync(Request("\t\u00A0Bus  Fare\u3000"),false,default);
 Assert(result.StatusCode==201 && result.Value!.Name=="Bus  Fare");
 Assert((await f.Db.Categories.SingleAsync()).UserId==1);
});
await Check("Boundary-only names rejected",async()=>{
 await using var f=new Fixture();
 foreach(var ch in new[]{9,32,133,160,5760,8192,8232,8239,8287,12288})
 Assert((await f.Service.CreateAsync(Request(new string((char)ch,3)),false,default)).StatusCode==400);
 Assert(!await f.Db.Categories.AnyAsync());
});
await Check("Inactive exact-name duplicates rejected; own update excluded",async()=>{
 await using var f=new Fixture();var first=await f.Service.CreateAsync(Request("Food",active:false),false,default);
 Assert((await f.Service.CreateAsync(Request("\tFood\u00A0"),false,default)).StatusCode==409);
 Assert((await f.Service.UpdateAsync(first.Value!.CategoryId,Request("Food"),false,default)).StatusCode==200);
});
await Check("Different owners and types remain separate",async()=>{
 await using var f=new Fixture();
 Assert((await f.Service.CreateAsync(Request("Food"),false,default)).StatusCode==201);
 Assert((await f.Service.CreateAsync(Request("Food","Income"),false,default)).StatusCode==201);
 f.Current.UserId=2;
 Assert((await f.Service.CreateAsync(Request("Food"),false,default)).StatusCode==201);
});
await Check("Null owner defaults protected and distinct from private scope",async()=>{
 await using var f=new Fixture();f.Current.IsAdministrator=true;
 Assert((await f.Service.CreateAsync(Request("Food"),true,default)).StatusCode==201);
 Assert((await f.Service.CreateAsync(Request("Food"),true,default)).StatusCode==409);
 Assert((await f.Service.CreateAsync(Request("Food"),false,default)).StatusCode==201);
 Assert(f.Writes.Resources.Contains(CategoryNameNormalization.SystemWriteLock));
});
await Check("Administrator and ownership restrictions preserved",async()=>{
 await using var f=new Fixture();
 Assert((await f.Service.CreateAsync(Request("Food"),true,default)).StatusCode==403);
 var created=await f.Service.CreateAsync(Request("Food"),false,default);f.Current.UserId=2;
 Assert((await f.Service.UpdateAsync(created.Value!.CategoryId,Request("Other"),false,default)).StatusCode==404);
 Assert((await f.Service.DeleteAsync(created.Value.CategoryId,false,default)).StatusCode==404);
});
await Check("Referenced rename preserves transaction and blocks type change/delete",async()=>{
 await using var f=new Fixture();var first=await f.Service.CreateAsync(Request("Food"),false,default);int id=first.Value!.CategoryId;
 f.Db.Transactions.Add(new(){UserId=1,CategoryId=id,ClientId=Guid.NewGuid(),Type=TransactionType.Expense,CurrencyCode="PHP",Amount=1,FxRate=1,AmountBase=1});
 await f.Db.SaveChangesAsync();
 Assert((await f.Service.UpdateAsync(id,Request("Renamed"),false,default)).StatusCode==200);
 Assert((await f.Service.UpdateAsync(id,Request("Renamed","Income"),false,default)).StatusCode==409);
 Assert((await f.Service.DeleteAsync(id,false,default)).StatusCode==409);
 Assert((await f.Db.Transactions.SingleAsync()).CategoryId==id);
});
foreach(var number in new[]{2601,2627})
 foreach(var update in new[]{false,true})
 await Check($"Synthetic {number} on {(update?"update":"create")} becomes safe 409 after write scope unwinds",async()=>{
 await using var f=new Fixture();var original=await f.Service.CreateAsync(Request("Original"),false,default);int id=original.Value!.CategoryId;
 f.Writes.Error=new DbUpdateException("SYNTHETIC_PRIVATE_SQL_DETAIL",SyntheticSqlError.Create(number));
 var result=update?await f.Service.UpdateAsync(id,Request("New"),false,default):await f.Service.CreateAsync(Request("New"),false,default);
 Assert(result.StatusCode==409 && result.Error=="A category with the same name and type already exists." && f.Writes.Unwound);
 Assert(!f.Db.ChangeTracker.Entries().Any());
 Assert((await f.Db.Categories.SingleAsync()).Name=="Original");
});
await Check("Anonymous writes remain unauthorized",async()=>{
 await using var f=new Fixture();f.Current.UserId=null;
 Assert((await f.Service.CreateAsync(Request("Food"),false,default)).StatusCode==401);
});
await Check("Unique-key translation does not blanket-handle category deletion",async()=>{
 await using var f=new Fixture();var row=await f.Service.CreateAsync(Request("Food"),false,default);
 f.Writes.Error=new DbUpdateException("Synthetic",SyntheticSqlError.Create(2601));bool propagated=false;
 try{await f.Service.DeleteAsync(row.Value!.CategoryId,false,default);}catch(DbUpdateException){propagated=true;}
 Assert(propagated);
});
Console.WriteLine($"Category uniqueness checks: {passed} passed, {failed} failed. In-memory boundary simulation/synthetic SQL errors; no SQL connection, collation or rollback certification.");
return failed==0?0:1;

sealed class Current : ICurrentUser{
 public int? UserId {get;set;}=1;public bool IsAdministrator {get;set;}
 public bool Owns(int owner)=>UserId==owner;
}
sealed class Writes : IFinancialWriteScope{
 public Exception? Error;public bool Unwound;public HashSet<string> Resources=[];
 public async Task<T> ExecuteAsync<T>(IEnumerable<string> resources,Func<CancellationToken,Task<T>> action,CancellationToken ct){
 Resources.UnionWith(resources);try{if(Error is not null)throw Error;return await action(ct);}finally{Unwound=true;}
 }
}
sealed class Fixture : IAsyncDisposable{
 public ApplicationDbContext Db {get;}=new(new DbContextOptionsBuilder<ApplicationDbContext>()
 .UseInMemoryDatabase(Guid.NewGuid().ToString()).AddInterceptors(new CategoryComputedValueSimulation()).Options);
 public Current Current {get;}=new();public Writes Writes {get;}=new();
 public CategoryService Service=>new(Db,Current,Writes);
 public ValueTask DisposeAsync()=>Db.DisposeAsync();
}
