# Copy OUTSIDE source control. Local development on a NEW machine only.
$env:DOTNET_ENVIRONMENT = 'Development'
$env:ASPNETCORE_ENVIRONMENT = 'Development'
$env:ConnectionStrings__DefaultConnection = 'Server=(localdb)\MSSQLLocalDB;Database=PersonalFinanceDb;Integrated Security=True;Encrypt=True;TrustServerCertificate=True'
# Generate a private random value as explained in ../README.md. Never use this placeholder.
$env:Jwt__SigningKeyBase64 = '<GENERATE-PRIVATE-RANDOM-BASE64>'
$env:Jwt__Issuer = 'PersonalFinance.Api'
$env:Jwt__Audience = 'PersonalFinance.Clients'
$env:BackendApi__BaseUrl = 'https://localhost:7027/'
$env:Hangfire__Enabled = 'false'
# Optional ONLY after installing the separate Hangfire schema:
# $env:ConnectionStrings__Hangfire = 'Server=(localdb)\MSSQLLocalDB;Database=OWLI_Hangfire;Integrated Security=True;Encrypt=True;TrustServerCertificate=True'
