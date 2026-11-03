# GuardianIA v3.9:
#   - Tor con PUENTES (bridges): si la red bloquea la red pública de Tor, se pegan puentes de
#     bridges.torproject.org directo en Protección → VPN → Tor, sin salir de GuardianIA.
#   - «Mi servidor GuardianIA VPN» se puede dejar YA CONFIGURADO en el equipo final: con
#     -GiaUrl/-GiaHuella/-GiaFicha (los 3 datos del instalador de vpn_servidor), el PC se conecta solo
#     al primer arranque y quien use el equipo NUNCA ve el formulario (no sabe qué es Google Cloud).
#
# Ejecutar desde la raíz del proyecto Android (donde está gradlew.bat):
#   powershell -ExecutionPolicy Bypass -File .\actualizar-v39\actualizar-v39.ps1 -Servidor "C:\ruta\a\GuardianIA" `
#       -GiaUrl "https://34.123.45.67:8844" -GiaHuella "AB:CD:...:EF" -GiaFicha "la-ficha-secreta"
#
# -GiaUrl/-GiaHuella/-GiaFicha/-GiaEmail son opcionales: sin ellos, todo lo demás se actualiza igual y
# el formulario manual («Mi servidor GuardianIA VPN» en el panel) sigue disponible como antes.
param(
  [string]$Servidor = "",
  [string]$GiaUrl = "",
  [string]$GiaHuella = "",
  [string]$GiaFicha = "",
  [string]$GiaEmail = ""
)
$ErrorActionPreference = "Stop"
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
$raiz = (Get-Location).Path
if (-not (Test-Path (Join-Path $raiz "gradlew.bat"))) { throw "Ejecute este script desde la raíz del proyecto Android (donde está gradlew.bat)." }
$sello = Get-Date -Format "yyyyMMdd-HHmmss"
$respaldo = Join-Path $raiz "respaldo-v39-$sello"
New-Item -ItemType Directory -Force $respaldo | Out-Null

# ── Android: solo el archivo que cambió (puentes de Tor). No se toca el resto de app\src.
$destTor = Join-Path $raiz "app\src\main\java\co\guardiania\app\vpn\TorInterno.java"
if (Test-Path $destTor) {
  $bkTor = Join-Path $respaldo "TorInterno.java"
  New-Item -ItemType Directory -Force (Split-Path $bkTor) | Out-Null
  Copy-Item $destTor $bkTor
  Copy-Item (Join-Path $aqui "android\src\main\java\co\guardiania\app\vpn\TorInterno.java") $destTor -Force
  Write-Host "Android actualizado (puentes de Tor en TorInterno.java). Original en: $bkTor" -ForegroundColor Green
} else {
  Write-Host "No encontré app\src\...\vpn\TorInterno.java: ¿aplicó primero actualizar-v38? Se omite Android." -ForegroundColor Yellow
}

# ── Servidor: copia lo cambiado (tor.py, la API y el panel de VPN) y, si los dio, guarda los 3 datos
#    de su servidor GuardianIA VPN en el .env para que se conecte solo al arrancar.
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

  if ($GiaUrl -and $GiaHuella -and $GiaFicha) {
    $envPath = Join-Path $Servidor ".env"
    $bkEnv = Join-Path $bkServ ".env"
    if (Test-Path $envPath) { New-Item -ItemType Directory -Force (Split-Path $bkEnv) | Out-Null; Copy-Item $envPath $bkEnv }
    $lineas = @()
    if (Test-Path $envPath) { $lineas = Get-Content $envPath | Where-Object { $_ -notmatch '^(GIA_URL|GIA_HUELLA|GIA_FICHA|GIA_EMAIL)=' } }
    $lineas += "GIA_URL=$GiaUrl"
    $lineas += "GIA_HUELLA=$GiaHuella"
    $lineas += "GIA_FICHA=$GiaFicha"
    if ($GiaEmail) { $lineas += "GIA_EMAIL=$GiaEmail" }
    Set-Content -Path $envPath -Value $lineas -Encoding UTF8
    Write-Host "Datos de su servidor GuardianIA VPN guardados en $envPath. Al próximo arranque (o al abrir" -ForegroundColor Green
    Write-Host "el panel), el PC se conecta solo: la persona que use este equipo no verá ese formulario." -ForegroundColor Green
  } else {
    Write-Host "No se pasaron -GiaUrl/-GiaHuella/-GiaFicha: el formulario manual sigue disponible en el panel." -ForegroundColor Cyan
  }
  Write-Host "Reinicie el servidor: python -m app.servidor" -ForegroundColor Cyan
} else {
  Write-Host "Servidor NO actualizado: repita con -Servidor 'C:\ruta\a\GuardianIA' (la carpeta que contiene app\servidor.py)." -ForegroundColor Cyan
}

Write-Host "`nCompilando (limpio)..."
& .\gradlew.bat clean assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "`nLa compilación falló. Copie aquí las líneas con 'error:'." -ForegroundColor Yellow; exit 1 }
Write-Host "`nListo: app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
