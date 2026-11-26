using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminProvisioning;
using PersonalFinance.AuthenticationTests;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Identity;
using PersonalFinance.Infrastructure.Authentication;

var passed=0;var failed=0;
void Assert(bool value) { if(!value) throw new Exception("Assertion failed"); }
async Task Check(string name,Func<Task> test)
{
 try {await test();passed++;Console.WriteLine("PASS "+name);}
 catch(Exception ex){failed++;Console.WriteLine("FAIL "+name+": "+ex.GetType().Name+": "+ex.Message);}
}
async Task Reject(Func<Task> action) {bool rejected=false;try{await action();}catch(ProvisioningException){rejected=true;}Assert(rejected);}
foreach(var env in new string?[]{null,"","Production","Staging","development"})
 await Check("Reject environment "+(env??"missing"),()=>Reject(()=>{ProvisioningSafety.RequireDevelopment(env,null);return Task.CompletedTask;}));
await Check("Reject conflicting environment",()=>Reject(()=>{ProvisioningSafety.RequireDevelopment("Development","Production");return Task.CompletedTask;}));
await Check("Allow explicit Development",()=>{ProvisioningSafety.RequireDevelopment("Development",null);return Task.CompletedTask;});
await Check("Reject empty connection configuration",()=>Reject(()=>{ProvisioningSafety.RequireTarget("");return Task.CompletedTask;}));
await Check("Reject whitespace connection configuration",()=>Reject(()=>{ProvisioningSafety.RequireTarget("   ");return Task.CompletedTask;}));
foreach(var target in new[]{
 @"Server=localhost;Database=PersonalFinanceDb;Integrated Security=True",
 @"Server=(localdb)\MSSQLLocalDB;Database=master;Integrated Security=True",
 @"Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;User ID=not-used",
 @"Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Integrated Security=True;AttachDbFilename=C:\wrong.mdf"})
 await Check("Reject unintended connection target",()=>Reject(()=>{ProvisioningSafety.RequireTarget(target);return Task.CompletedTask;}));
await Check("Accept expected resolved connection without connecting",()=>{
 ProvisioningSafety.RequireTarget(@"Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Trusted_Connection=True;Encrypt=True;TrustServerCertificate=True");
 return Task.CompletedTask;
});
foreach(var target in new[]{
 "Server=localhost,14336;Database=OWLI_Runtime_;User ID=sa;Password=x",
 "Server=localhost,14336;Database=OWLI_Runtime_x;y;User ID=sa;Password=x",
 "Server=localhost,14336;Database=OWLI_Hangfire;User ID=sa;Password=x",
 "Server=localhost,14336;Database=OWLI_Runtime_20261126;AttachDbFilename=/tmp/x.mdf;User ID=sa;Password=x"})
 await Check("Reject unapproved portable target",()=>Reject(()=>{ProvisioningSafety.RequireTarget(target);return Task.CompletedTask;}));
await Check("Accept disposable runtime catalog on any server with SQL authentication",()=>{
 Assert(ProvisioningSafety.RequireTarget("Server=localhost,14336;Database=OWLI_Runtime_20261126;User ID=sa;Password=x;TrustServerCertificate=True")=="OWLI_Runtime_20261126");
 Assert(ProvisioningSafety.ConfirmationFor("OWLI_Runtime_20261126")=="CREATE DEVELOPMENT ADMINISTRATOR IN OWLI_Runtime_20261126");
 Assert(ProvisioningSafety.ConfirmationFor("PersonalFinanceDb")==ProvisioningSafety.Confirmation);
 return Task.CompletedTask;
});
await Check("Wrong confirmation prevents database verification and writes",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Create(confirmation:"no"));
 Assert(f.Database.Verifications==0 && f.Store.Users.Count==0);
});
await Check("Nondevelopment service call prevents database access",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Service.CreateAsync("Production",null,ProvisioningSafety.Confirmation,"new@example.test","new.admin","Person",Fixture.Password,Fixture.Password));
 Assert(f.Database.Verifications==0);
});
await Check("Successful Identity creation assigns both roles and stores a hash",async()=>{
 using var f=new ProvisionFixture();var id=await f.Create();
 Assert(f.Store.Membership[id].SetEquals(["USER","ADMINISTRATOR"]));
 Assert(f.Store.Users[id].PasswordHash is not null && f.Store.Users[id].PasswordHash!=Fixture.Password);
 Assert(f.Store.Users[id].BaseCurrency=="PHP" && f.Store.Users[id].LockoutEnabled);
 Assert(f.Database.Commits==1);
});
await Check("Selected demo identifiers normalize independently",async()=>{
 using var f=new ProvisionFixture();
 var id=await f.Create(email:"admin.demo@demo.test",userName:"admin.demo",fullName:"Admin Demo");
 var user=f.Store.Users[id];
 Assert(user.Email=="admin.demo@demo.test" && user.UserName=="admin.demo" && user.FullName=="Admin Demo");
 Assert(user.NormalizedEmail=="ADMIN.DEMO@DEMO.TEST" && user.NormalizedUserName=="ADMIN.DEMO");
 Assert(f.Store.Membership[id].SetEquals(["USER","ADMINISTRATOR"]));
});
await Check("Trim surrounding whitespace and preserve internal username characters",async()=>{
 using var f=new ProvisionFixture();var id=await f.Create(email:" admin.demo@demo.test ",userName:" admin.demo ",fullName:" Admin Demo ");
 var user=f.Store.Users[id];
 Assert(user.Email=="admin.demo@demo.test" && user.UserName=="admin.demo" && user.FullName=="Admin Demo");
});
foreach(var userName in new[]{""," ","\t\r\n","bad name","bad/name","admin\u200Bdemo",new string('a',257)})
 await Check("Invalid username rejected before database access",async()=>{
  using var f=new ProvisionFixture();await Reject(()=>f.Create(userName:userName));
  Assert(f.Database.Verifications==0 && f.Store.Users.Count==0);
 });
await Check("Duplicate normalized username with distinct email preserves existing user",async()=>{
 using var f=new ProvisionFixture();
 var original=new ApplicationUser{Email="existing@example.test",UserName="admin.demo",FullName="Existing"};
 Assert((await f.Users.CreateAsync(original,Fixture.Password)).Succeeded);
 Assert((await f.Users.AddToRoleAsync(original,"User")).Succeeded);
 var before=(original.Email,original.UserName,original.PasswordHash,original.SecurityStamp);
 await Reject(()=>f.Create(email:"unused@example.test",userName:" ADMIN.DEMO "));
 Assert(f.Store.Users.Count==1 && f.Store.Membership[original.Id].SetEquals(["USER"]));
 Assert(before==(original.Email,original.UserName,original.PasswordHash,original.SecurityStamp));
});
foreach(var role in new[]{"USER","ADMINISTRATOR"})
 await Check("Missing required role prevents creation: "+role,async()=>{
  using var f=new ProvisionFixture(missingRole:role);await Reject(()=>f.Create());
  Assert(f.Store.Users.Count==0 && f.Store.Membership.Count==0 && f.Database.Commits==0 && f.Database.Rollbacks==1);
 });
await Check("Separate username retains email-only authentication",async()=>{
 using var f=new ProvisionFixture();
 await f.Create(email:"admin.demo@demo.test",userName:"admin.demo");
 var result=await f.Auth.LoginAsync(new(){Email="admin.demo@demo.test",Password=Fixture.Password},default);
 Assert(result.Succeeded && result.Value!.User.Roles.Contains("Administrator"));
 var usernameLogin=await f.Auth.LoginAsync(new(){Email="admin.demo",Password=Fixture.Password},default);
 Assert(!usernameLogin.Succeeded);
});
await Check("Duplicate normalized email preserves both regular accounts",async()=>{
 using var f=new ProvisionFixture();
 foreach(var email in new[]{"first@example.test","second@example.test"}){
 var user=new ApplicationUser{Email=email,UserName=email,FullName="Regular"};
 Assert((await f.Users.CreateAsync(user,Fixture.Password)).Succeeded);
 Assert((await f.Users.AddToRoleAsync(user,"User")).Succeeded);
 }
 var originals=f.Store.Users.Values.ToDictionary(u=>u.Id,u=>(u.PasswordHash,u.SecurityStamp));
 await Reject(()=>f.Create(email:" FIRST@EXAMPLE.TEST "));
 Assert(f.Store.Users.Count==2 && f.Store.Membership.Values.All(r=>r.SetEquals(["USER"])));
 Assert(f.Store.Users.Values.All(u=>originals[u.Id]==(u.PasswordHash,u.SecurityStamp)));
});
await Check("Identity password rejection rolls back",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Create(password:"aaaaaaaaaaaa"));
 Assert(f.Store.Users.Count==0 && f.Database.Rollbacks==1);
});
await Check("Password confirmation mismatch prevents database access",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Service.CreateAsync("Development",null,ProvisioningSafety.Confirmation,"new@example.test","new.admin","Person",Fixture.Password,"different"));
 Assert(f.Database.Verifications==0);
});
await Check("Role assignment failure rolls back new user and memberships",async()=>{
 using var f=new ProvisionFixture(failRoleAssignment:true);
 await Reject(()=>f.Create());
 Assert(f.Store.Users.Count==0 && f.Store.Membership.Count==0 && f.Database.Rollbacks==1);
});
await Check("Failure after successful assignment rolls back entire operation",async()=>{
 using var f=new ProvisionFixture();f.Database.FailBeforeCommit=true;
 await Reject(()=>f.Create());
 Assert(f.Store.Users.Count==0 && f.Store.Membership.Count==0 && f.Database.Rollbacks==1);
});
Console.WriteLine($"Provisioning checks: {passed} passed, {failed} failed. Real Identity; test transaction/store doubles. No SQL connection or console provisioning execution.");
return failed==0?0:1;

sealed class ProvisionFixture : IDisposable
{
 readonly ServiceProvider provider;readonly IServiceScope scope;
 public FakeIdentityStore Store {get;}=new();
 public MemoryProvisionDatabase Database {get;}
 public UserManager<ApplicationUser> Users {get;}
 public AdministratorProvisioner Service {get;}
 public IAuthenticationService Auth {get;}
 public ProvisionFixture(bool failRoleAssignment=false,string? missingRole=null)
 {
  var services=new ServiceCollection();services.AddLogging();services.AddInfrastructure(new ConfigurationBuilder().Build());
  services.AddDataProtection().UseEphemeralDataProtectionProvider();
  services.AddSingleton<IUserStore<ApplicationUser>>(Store);
  services.AddSingleton<IRoleStore<IdentityRole<int>>>(new ProvisionRoleStore(missingRole));
  services.AddSingleton<IAuthSessionStore>(new FakeSessionStore());
  services.AddSingleton<IAccessTokenIssuer>(new FakeAccessTokenIssuer(new TestClock()));
  if(failRoleAssignment) services.AddSingleton<IUserValidator<ApplicationUser>>(new RejectAssignedUser(Store));
  provider=services.BuildServiceProvider();scope=provider.CreateScope();
  Users=scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
  Auth=scope.ServiceProvider.GetRequiredService<IAuthenticationService>();
  Database=new(Store);
  Service=new(Users,scope.ServiceProvider.GetRequiredService<RoleManager<IdentityRole<int>>>(),Database);
 }
 public Task<int> Create(string email="new@example.test",string password=Fixture.Password,string confirmation=ProvisioningSafety.Confirmation,
  string userName="new.admin",string fullName="Development Administrator")
 =>Service.CreateAsync("Development",null,confirmation,email,userName,fullName,password,password);
 public void Dispose(){scope.Dispose();provider.Dispose();}
}
sealed class ProvisionRoleStore(string? missingRole) : IRoleStore<IdentityRole<int>>
{
 readonly IRoleStore<IdentityRole<int>> inner=new FakeRoleStore();
 public void Dispose()=>inner.Dispose();
 public Task<IdentityRole<int>?> FindByNameAsync(string name,CancellationToken ct)
  =>name==missingRole?Task.FromResult<IdentityRole<int>?>(null):inner.FindByNameAsync(name,ct);
 public Task<IdentityRole<int>?> FindByIdAsync(string id,CancellationToken ct)=>inner.FindByIdAsync(id,ct);
 public Task<string> GetRoleIdAsync(IdentityRole<int> role,CancellationToken ct)=>inner.GetRoleIdAsync(role,ct);
 public Task<string?> GetRoleNameAsync(IdentityRole<int> role,CancellationToken ct)=>inner.GetRoleNameAsync(role,ct);
 public Task SetRoleNameAsync(IdentityRole<int> role,string? name,CancellationToken ct)=>inner.SetRoleNameAsync(role,name,ct);
 public Task<string?> GetNormalizedRoleNameAsync(IdentityRole<int> role,CancellationToken ct)=>inner.GetNormalizedRoleNameAsync(role,ct);
 public Task SetNormalizedRoleNameAsync(IdentityRole<int> role,string? name,CancellationToken ct)=>inner.SetNormalizedRoleNameAsync(role,name,ct);
 public Task<IdentityResult> CreateAsync(IdentityRole<int> role,CancellationToken ct)=>throw new NotSupportedException();
 public Task<IdentityResult> UpdateAsync(IdentityRole<int> role,CancellationToken ct)=>throw new NotSupportedException();
 public Task<IdentityResult> DeleteAsync(IdentityRole<int> role,CancellationToken ct)=>throw new NotSupportedException();
}
sealed class RejectAssignedUser(FakeIdentityStore store) : IUserValidator<ApplicationUser>
{
 public Task<IdentityResult> ValidateAsync(UserManager<ApplicationUser> manager,ApplicationUser user)
 =>Task.FromResult(store.Membership.TryGetValue(user.Id,out var roles)&&roles.Contains("ADMINISTRATOR")
 ? IdentityResult.Failed(new IdentityError{Code="SimulatedAssignmentFailure"}):IdentityResult.Success);
}
sealed class MemoryProvisionDatabase(FakeIdentityStore store) : IProvisioningDatabase
{
 public int Verifications,Commits,Rollbacks;public bool FailBeforeCommit;
 public string Catalog => "PersonalFinanceDb";
 public Task VerifyAsync(CancellationToken ct){Verifications++;return Task.CompletedTask;}
 public async Task<T> ExecuteAsync<T>(Func<Task<T>> action,CancellationToken ct)
 {
  var originalIds=store.Users.Keys.ToHashSet();
  try{var result=await action();if(FailBeforeCommit)throw new ProvisioningException("Simulated commit failure");Commits++;return result;}
  catch{
   foreach(var id in store.Users.Keys.Where(id=>!originalIds.Contains(id)).ToArray()){store.Users.Remove(id);store.Membership.Remove(id);}
   Rollbacks++;throw;
  }
 }
}
