using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using PersonalFinance.AdminProvisioning;
using PersonalFinance.AuthenticationTests;
using PersonalFinance.Infrastructure;
using PersonalFinance.Infrastructure.Identity;

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
await Check("Wrong confirmation prevents database verification and writes",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Create(confirmation:"no"));
 Assert(f.Database.Verifications==0 && f.Store.Users.Count==0);
});
await Check("Nondevelopment service call prevents database access",async()=>{
 using var f=new ProvisionFixture();
 await Reject(()=>f.Service.CreateAsync("Production",null,ProvisioningSafety.Confirmation,"new@example.test","Person",Fixture.Password,Fixture.Password));
 Assert(f.Database.Verifications==0);
});
await Check("Successful Identity creation assigns both roles and stores a hash",async()=>{
 using var f=new ProvisionFixture();var id=await f.Create();
 Assert(f.Store.Membership[id].SetEquals(["USER","ADMINISTRATOR"]));
 Assert(f.Store.Users[id].PasswordHash is not null && f.Store.Users[id].PasswordHash!=Fixture.Password);
 Assert(f.Store.Users[id].BaseCurrency=="PHP" && f.Store.Users[id].LockoutEnabled);
 Assert(f.Database.Commits==1);
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
 await Reject(()=>f.Service.CreateAsync("Development",null,ProvisioningSafety.Confirmation,"new@example.test","Person",Fixture.Password,"different"));
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
 public ProvisionFixture(bool failRoleAssignment=false)
 {
  var services=new ServiceCollection();services.AddLogging();services.AddInfrastructure(new ConfigurationBuilder().Build());
  services.AddDataProtection().UseEphemeralDataProtectionProvider();
  services.AddSingleton<IUserStore<ApplicationUser>>(Store);
  services.AddSingleton<IRoleStore<IdentityRole<int>>>(new FakeRoleStore());
  if(failRoleAssignment) services.AddSingleton<IUserValidator<ApplicationUser>>(new RejectAssignedUser(Store));
  provider=services.BuildServiceProvider();scope=provider.CreateScope();
  Users=scope.ServiceProvider.GetRequiredService<UserManager<ApplicationUser>>();
  Database=new(Store);
  Service=new(Users,scope.ServiceProvider.GetRequiredService<RoleManager<IdentityRole<int>>>(),Database);
 }
 public Task<int> Create(string email="new@example.test",string password=Fixture.Password,string confirmation=ProvisioningSafety.Confirmation)
 =>Service.CreateAsync("Development",null,confirmation,email,"Development Administrator",password,password);
 public void Dispose(){scope.Dispose();provider.Dispose();}
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
