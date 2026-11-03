# Restaura Sesion.java y Asistente.java de GuardianIA v2.9 y compila.
# Uso: copie esta carpeta dentro del proyecto y ejecute desde la raíz del proyecto:
#   powershell -ExecutionPolicy Bypass -File .\restaurar\restaurar.ps1
$ErrorActionPreference = "Stop"
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
$raiz = (Get-Location).Path
$base = Join-Path $raiz "app\src\main\java\co\guardiania\app"
if (-not (Test-Path $base)) { throw "Ejecute este script desde la raíz del proyecto (donde está gradlew.bat)." }

$sello = Get-Date -Format "yyyyMMdd-HHmmss"
$pares = @(
  @{ origen = "Sesion.java";    destino = Join-Path $base "net\Sesion.java" },
  @{ origen = "Asistente.java"; destino = Join-Path $base "ai\Asistente.java" }
)
foreach ($p in $pares) {
  if (Test-Path $p.destino) {
    Copy-Item $p.destino "$($p.destino).$sello.bak"          # copia de su versión modificada
    Write-Host "Copia de seguridad: $($p.destino).$sello.bak"
  }
  Copy-Item (Join-Path $aqui $p.origen) $p.destino -Force
  Write-Host "Restaurado: $($p.destino)"
}

# Los .bak no se compilan (Gradle solo toma .java), pero se mueven fuera para no confundir.
$respaldo = Join-Path $raiz "respaldo-$sello"
New-Item -ItemType Directory -Force $respaldo | Out-Null
Get-ChildItem $base -Recurse -Filter "*.bak" | Move-Item -Destination $respaldo
Write-Host "Versiones modificadas guardadas en: $respaldo"

Write-Host "`nCompilando…"
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) {
  Write-Host "`nLa compilación falló. Copie aquí las PRIMERAS líneas de error (las que dicen 'error:')." -ForegroundColor Yellow
  exit 1
}
Write-Host "`nListo: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
