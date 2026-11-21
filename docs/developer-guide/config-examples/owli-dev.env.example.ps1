# EXAMPLE ONLY - per-terminal environment for local OWLI development. Contains no secrets.
# Usage:  . .\docs\developer-guide\config-examples\owli-dev.env.example.ps1

# --- Web API (normal laptop mode: launch profile https -> https://localhost:7027) ---
$env:ASPNETCORE_ENVIRONMENT = "Development"
$env:Hangfire__Enabled      = "false"          # keep disabled: Hangfire schema is not installed
# Secrets live in user-secrets (see BACKEND_SETUP.md):
#   dotnet user-secrets --project PersonalFinance.Api set "Jwt:SigningKeyBase64" <generated locally>
#   dotnet user-secrets --project PersonalFinance.Api set "ConnectionStrings:DefaultConnection" "<LocalDB connection string>"

# --- Web API (Android phone mode only; then run with --no-launch-profile) ---
# $env:OWLI_DEV_CERT_THUMBPRINT = "<40-hex thumbprint of YOUR localhost dev certificate>"

# --- MVC admin site (separate terminal) ---
$env:BackendApi__BaseUrl = "https://localhost:7027"

# --- Admin provisioning tool (only with owner approval) ---
# $env:DOTNET_ENVIRONMENT = "Development"
