$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "Created .env from .env.example. Change the passwords and keys before production use." -ForegroundColor Yellow
}
docker compose up --build -d
docker compose ps
Write-Host "TalentShift: http://localhost:8080"
Write-Host "Swagger UI: http://localhost:8080/swagger-ui.html"
Write-Host "pgAdmin: http://localhost:5050"
