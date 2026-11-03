# GuardianIA v3.1: IA en segundo plano, historial de chat, modo cebolla (Tor) y diseño renovado.
# Ejecutar desde la raíz del proyecto Android (donde está gradlew.bat):
#   powershell -ExecutionPolicy Bypass -File .\actualizar-v31\actualizar-v31.ps1 -Servidor "C:\ruta\a\GuardianIA"
param([string]$Servidor = "")
$ErrorActionPreference = "Stop"
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
$raiz = (Get-Location).Path
if (-not (Test-Path (Join-Path $raiz "gradlew.bat"))) { throw "Ejecute este script desde la raíz del proyecto Android (donde está gradlew.bat)." }
$sello = Get-Date -Format "yyyyMMdd-HHmmss"
$respaldo = Join-Path $raiz "respaldo-v31-$sello"
New-Item -ItemType Directory -Force $respaldo | Out-Null

# ── Android: se reemplaza app\src completo (así desaparecen los archivos de Salud).
$src = Join-Path $raiz "app\src"
if (Test-Path $src) { Move-Item $src (Join-Path $respaldo "app-src") }
Copy-Item (Join-Path $aqui "android\src") $src -Recurse
$gradle = Join-Path $raiz "app\build.gradle"
if (Test-Path $gradle) { Copy-Item $gradle (Join-Path $respaldo "build.gradle") }
Copy-Item (Join-Path $aqui "android\build.gradle") $gradle -Force
Copy-Item (Join-Path $aqui "LEER_PRIMERO.md") (Join-Path $raiz "LEER_PRIMERO.md") -Force
Write-Host "Android actualizado. Su app\src anterior quedó en: $respaldo\app-src" -ForegroundColor Green

# ── Servidor (opcional): copia lo cambiado y aparta lo retirado.
if ($Servidor) {
  if (-not (Test-Path (Join-Path $Servidor "app\servidor.py"))) { throw "La ruta -Servidor no es el proyecto Python (falta app\servidor.py): $Servidor" }
  $origen = Join-Path $aqui "servidor"
  $bkServ = Join-Path $respaldo "servidor"
  Get-ChildItem $origen -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($origen.Length + 1)
    $dest = Join-Path $Servidor $rel
    if (Test-Path $dest) {
      $bk = Join-Path $bkServ $rel
      New-Item -ItemType Directory -Force (Split-Path $bk) | Out-Null
      Copy-Item $dest $bk
    }
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
    Write-Host "Servidor actualizado: $rel"
  }
  Get-Content (Join-Path $aqui "servidor-retirados.txt") | Where-Object { $_.Trim() } | ForEach-Object {
    $rel = $_.Trim().Replace("/", "\")
    $f = Join-Path $Servidor $rel
    if (Test-Path $f) {
      $bk = Join-Path $bkServ ("retirados\" + $rel)
      New-Item -ItemType Directory -Force (Split-Path $bk) | Out-Null
      Move-Item $f $bk
      Write-Host "Servidor retirado (guardado en respaldo): $rel" -ForegroundColor Yellow
    }
  }
  Write-Host "Instale la dependencia nueva (Tor por SOCKS): pip install -r requirements.txt" -ForegroundColor Cyan
  Write-Host "Reinicie el servidor: python -m app.servidor" -ForegroundColor Cyan
} else {
  Write-Host "Servidor NO actualizado: repita con -Servidor 'C:\ruta\a\GuardianIA' (la carpeta que contiene app\servidor.py)." -ForegroundColor Cyan
}

Write-Host "`nCompilando (limpio)..."
& .\gradlew.bat clean assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "`nLa compilación falló. Copie aquí las líneas con 'error:'." -ForegroundColor Yellow; exit 1 }
Write-Host "`nListo: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
