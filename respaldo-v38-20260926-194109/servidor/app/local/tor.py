"""Modo cebolla (Tor) del PC: gratis, con el software OFICIAL del Tor Project.

Tor envía el tráfico por 3 relevos voluntarios, cifrado en capas (como una cebolla): el primero sabe
quién es usted pero no qué visita; el último sabe qué se visita pero no quién lo pide. Ninguno conoce
las dos cosas. Es más anónimo que una VPN (que confía en un solo operador), pero más lento.

Decisiones de seguridad (no se reimplementa Tor: un Tor casero sería inseguro):

* Se usa el ``tor.exe`` oficial: el que trae Tor Browser o el «Tor Expert Bundle». Solo se buscan
  rutas conocidas; la API NUNCA acepta una ruta de programa (evita ejecutar binarios arbitrarios).
* Tor escucha solo en 127.0.0.1: nadie más en la red de la casa puede usarlo como proxy.
* Se usa ``socks5h``: los nombres de los sitios se resuelven DENTRO de Tor (sin fuga de DNS).
* La verificación la hace el propio Tor Project (https://check.torproject.org/api/ip → IsTor).
* Si Tor Browser ya está abierto, se reutiliza su Tor (puerto 9150) en vez de lanzar otro.

Límite honesto: esto pasa por Tor el tráfico de GuardianIA y de las apps que se configuren con el
proxy SOCKS 127.0.0.1:9050. Para navegar de forma anónima lo correcto es Tor Browser, que además
oculta la huella del navegador.
"""
from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path

import httpx

from app.local import ajustes, sistema

PUERTO_PROPIO = 9050          # el Tor que lanza GuardianIA
PUERTO_TOR_BROWSER = 9150     # el Tor de Tor Browser abierto
PUERTO_CELULAR = 9080         # proxy HTTP (CONNECT) de Tor compartido con el celular, solo en la red local
CHECK_URL = "https://check.torproject.org/api/ip"
TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"   # «loc=XX»: el país que ven los sitios

# Países de salida que se pueden elegir (lista cerrada: el código se pasa a Tor y NUNCA se acepta texto
# libre, así nadie puede inyectar opciones en su configuración).
PAISES = {
    "US": "Estados Unidos", "CA": "Canadá", "MX": "México", "BR": "Brasil", "AR": "Argentina", "CL": "Chile",
    "ES": "España", "GB": "Reino Unido", "FR": "Francia", "DE": "Alemania", "NL": "Países Bajos",
    "CH": "Suiza", "SE": "Suecia", "IT": "Italia", "PL": "Polonia", "RO": "Rumania", "JP": "Japón",
    "SG": "Singapur", "AU": "Australia", "IN": "India",
}

_proceso: subprocess.Popen | None = None
_progreso = {"porcentaje": 0, "resumen": "", "error": "", "desde": 0.0}
ATASCADO_S = 90               # si no llega a 100 % en este tiempo, probablemente la red bloquea Tor
_lock = threading.Lock()


class ErrorTor(Exception):
    pass


def candidatos() -> list[Path]:
    """Rutas conocidas del tor.exe oficial (Tor Browser o Expert Bundle), en orden de preferencia."""
    exe = "tor.exe" if sistema.ES_WINDOWS else "tor"
    rutas: list[Path] = [ajustes.carpeta() / "tor" / "tor" / exe, ajustes.carpeta() / "tor" / exe]
    home = Path.home()
    for base in [home / "Desktop", home / "Escritorio", home / "OneDrive" / "Escritorio", home / "OneDrive" / "Desktop",
                 Path(os.environ.get("LOCALAPPDATA", "")), Path(os.environ.get("ProgramFiles", "")),
                 Path(os.environ.get("ProgramFiles(x86)", ""))]:
        rutas.append(base / "Tor Browser" / "Browser" / "TorBrowser" / "Tor" / exe)
    # Ruta fijada por la persona en su .env (configuración del servidor, nunca desde la API).
    if os.environ.get("GUARDIAN_TOR_EXE"):
        rutas.insert(0, Path(os.environ["GUARDIAN_TOR_EXE"]))
    # Otras ubicaciones donde suele quedar Tor Browser (instalador, winget, carpeta Descargas).
    for base in [home / "Downloads", home / "Descargas", Path(os.environ.get("LOCALAPPDATA", "")) / "Programs"]:
        rutas.append(base / "Tor Browser" / "Browser" / "TorBrowser" / "Tor" / exe)
    en_path = shutil.which(exe)
    if en_path:
        rutas.append(Path(en_path))
    vistos, unicos = set(), []
    for r in rutas:
        if str(r) not in vistos:
            vistos.add(str(r))
            unicos.append(r)
    return unicos


def localizar() -> Path | None:
    for r in candidatos():
        try:
            if r.is_file():
                return r
        except OSError:
            continue
    return None


def _puerto_abierto(puerto: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", puerto), timeout=0.6):
            return True
    except OSError:
        return False


def puerto_activo() -> int | None:
    """Puerto SOCKS de un Tor ya corriendo: el propio (9050) o el de Tor Browser (9150)."""
    for p in (PUERTO_PROPIO, PUERTO_TOR_BROWSER):
        if _puerto_abierto(p):
            return p
    return None


def verificar(puerto: int, timeout: float = 25.0) -> dict:
    """Pregunta al Tor Project, A TRAVÉS de Tor, si la conexión sale por la red Tor."""
    try:
        with httpx.Client(proxy=f"socks5h://127.0.0.1:{puerto}", timeout=timeout) as c:
            d = c.get(CHECK_URL).json()
            pais_real = None
            try:           # el país que ven los sitios (para confirmar el país de salida elegido)
                for linea in c.get(TRACE_URL).text.splitlines():
                    if linea.startswith("loc="):
                        pais_real = linea[4:].strip().upper()
            except Exception:  # noqa: BLE001 - sin país no se invalida la verificación de Tor
                pais_real = None
        r = {"is_tor": bool(d.get("IsTor")), "ip_salida": d.get("IP"), "pais_real": pais_real}
        elegido = pais_salida()
        if elegido and pais_real:
            r["pais_confirmado"] = pais_real == elegido
        return r
    except ImportError:
        return {"is_tor": False, "error": "Falta el complemento SOCKS: pip install socksio"}
    except (httpx.HTTPError, ValueError) as e:
        return {"is_tor": False, "error": f"No se pudo verificar ({type(e).__name__})"}


def _leer_arranque(proc: subprocess.Popen) -> None:
    """Sigue el registro de Tor para mostrar el avance («Bootstrapped 45%: Loading relay descriptors»)."""
    patron = re.compile(r"Bootstrapped (\d+)%[^:]*: (.*)")
    for linea in iter(proc.stdout.readline, ""):
        m = patron.search(linea)
        if m:
            with _lock:
                _progreso["porcentaje"] = int(m.group(1))
                _progreso["resumen"] = m.group(2).strip()
        elif "[err]" in linea.lower() or ("[warn]" in linea.lower() and "Could not bind" in linea):
            with _lock:
                _progreso["error"] = linea.strip()[-200:]


def compartido() -> bool:
    """¿El Tor del PC se comparte con el celular? Apagado por defecto (lo decide la persona)."""
    return bool(ajustes.leer().get("tor_compartir_lan", False))


def ips_lan() -> list[str]:
    """IPv4 privadas de este PC en la red de la casa (sin 127.x, sin la del túnel VPN)."""
    import ipaddress
    from app import tls
    tunel = set()
    try:
        from app.local import vpn
        p = vpn.perfil() or {}
        for a in str((p.get("interfaz") or {}).get("Address", "")).split(","):
            tunel.add(a.strip().split("/")[0])
    except Exception:  # noqa: BLE001 - sin perfil de VPN no hay nada que excluir
        pass
    tunel.add("172.16.0.2")           # dirección que Cloudflare WARP le da al PC
    salida = []
    for ip in tls.ips_locales():
        try:
            d = ipaddress.ip_address(ip)
        except ValueError:
            continue
        if d.version == 4 and d.is_private and not d.is_loopback and ip not in tunel:
            salida.append(ip)
    return salida


def aislamiento() -> bool:
    """¿Un circuito por sitio (anonimato máximo)? Lo decide el objetivo de la pasarela de privacidad."""
    return bool(ajustes.leer().get("tor_aislamiento", False))


def cookie_control() -> Path:
    """Cookie de autenticación del puerto de control (Tor la escribe en su carpeta de datos privada)."""
    return ajustes.sub("tor_datos") / "control_auth_cookie"


def pais_salida() -> str | None:
    """País de salida elegido (código ISO de la lista cerrada) o None = el que Tor elija."""
    p = ajustes.leer().get("tor_pais")
    return p if p in PAISES else None


def fijar_pais(pais: str | None) -> dict:
    """Elige el país por el que Tor sale a internet (o None para cualquiera). Reinicia el Tor propio."""
    if pais is not None:
        pais = pais.upper()
        if pais not in PAISES:
            raise ErrorTor(f"País no disponible: {pais}")
    ajustes.guardar({"tor_pais": pais})
    if _proceso is not None and _proceso.poll() is None:
        detener()
        iniciar()
    return estado()


def fijar_compartir(activo: bool) -> dict:
    """Comparte (o deja de compartir) el Tor del PC con el celular. Reinicia el Tor propio si corre."""
    ajustes.guardar({"tor_compartir_lan": bool(activo)})
    if _proceso is not None and _proceso.poll() is None:
        detener()
        iniciar()
    return estado()


def iniciar() -> dict:
    """Arranca Tor (o reutiliza el que ya corre). No bloquea: el avance se consulta con estado()."""
    global _proceso
    ya = puerto_activo()
    if ya:
        return estado()
    exe = localizar()
    if exe is None:
        raise ErrorTor("No encontré Tor en este equipo. Instale Tor Browser (gratis, de torproject.org) y "
                       "vuelva a intentar: GuardianIA usará el Tor que trae.")
    datos = ajustes.sub("tor_datos")
    with _lock:
        _progreso.update(porcentaje=0, resumen="Iniciando Tor…", error="", desde=time.time())
    # Anonimato máximo: cada sitio (destino y puerto) va por un circuito distinto, así ningún relevo
    # puede relacionar sus visitas. En modo velocidad se reutilizan circuitos (menos espera).
    socks = f"127.0.0.1:{PUERTO_PROPIO}" + (" IsolateDestAddr IsolateDestPort" if aislamiento() else "")
    from app.local import tor_control
    argumentos = [str(exe), "--SocksPort", socks, "--DataDirectory", str(datos),
                  "--ControlPort", f"127.0.0.1:{tor_control.PUERTO_CONTROL}", "--CookieAuthentication", "1",
                  "--ClientOnly", "1", "--AvoidDiskWrites", "1", "--Log", "notice stdout"]
    pais = pais_salida()
    if pais:
        # Solo salir por relevos de ese país. StrictNodes: si no hay, NO sale por otro (no engaña).
        argumentos += ["--ExitNodes", "{" + pais.lower() + "}", "--StrictNodes", "1"]
    if compartido():
        # Proxy HTTP (CONNECT) solo en las IP privadas de la casa: el celular lo usa sin Orbot.
        # Los nombres se resuelven dentro de Tor (sin fuga de DNS). Nunca en 0.0.0.0.
        for ip in ips_lan():
            argumentos += ["--HTTPTunnelPort", f"{ip}:{PUERTO_CELULAR}"]
    banderas = subprocess.CREATE_NO_WINDOW if sistema.ES_WINDOWS else 0
    _proceso = subprocess.Popen(argumentos, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                cwd=str(exe.parent), creationflags=banderas)
    threading.Thread(target=_leer_arranque, args=(_proceso,), daemon=True).start()
    return estado()


def detener() -> dict:
    """Apaga el Tor que lanzó GuardianIA (el de Tor Browser no se toca)."""
    global _proceso
    if _proceso and _proceso.poll() is None:
        _proceso.terminate()
        try:
            _proceso.wait(timeout=10)
        except subprocess.TimeoutExpired:
            _proceso.kill()
    _proceso = None
    with _lock:
        _progreso.update(porcentaje=0, resumen="", error="")
    return estado()


def estado(verificar_salida: bool = False) -> dict:
    propio = _proceso is not None and _proceso.poll() is None
    puerto = puerto_activo()
    with _lock:
        prog = dict(_progreso)
    origen = "guardian" if propio else "tor_browser" if puerto == PUERTO_TOR_BROWSER else "externo" if puerto else None
    listo = bool(puerto) and (prog["porcentaje"] >= 100 or not propio)
    atascado = propio and not listo and prog["desde"] and time.time() - prog["desde"] > ATASCADO_S
    if atascado and not prog["error"]:
        prog["error"] = ("Tor no logra conectarse a la red. Puede que su red o su operador bloqueen Tor: pruebe con "
                         "otra red, o use Tor Browser y en su configuración elija «Usar un puente» (bridges).")
    r = {"instalado": localizar() is not None or bool(puerto), "activo": bool(puerto), "listo": listo,
         "origen": origen, "puerto_socks": puerto, "progreso": prog["porcentaje"], "resumen": prog["resumen"],
         "error": prog["error"],
         "como_usar": "Configure el navegador o la app con proxy SOCKS5 127.0.0.1:{} y active «DNS por el proxy». "
                      "Para navegar de forma anónima, lo mejor es Tor Browser.".format(puerto or PUERTO_PROPIO)}
    elegido = pais_salida()
    r["pais"] = {"elegido": elegido, "nombre": PAISES.get(elegido, "Cualquiera (el más rápido)"),
                 "disponibles": [{"codigo": k, "nombre": v} for k, v in PAISES.items()]}
    comparte = compartido()
    r["compartir"] = {
        "activo": comparte,
        "disponible": propio,                  # solo el Tor que lanza GuardianIA puede compartirse
        "direcciones": [f"{ip}:{PUERTO_CELULAR}" for ip in ips_lan()] if comparte and propio and listo else [],
        "puerto": PUERTO_CELULAR,
        "firewall": ('New-NetFirewallRule -DisplayName "GuardianIA Tor celular" -Direction Inbound -Protocol TCP '
                     f'-LocalPort {PUERTO_CELULAR} -RemoteAddress LocalSubnet -Action Allow'),
    }
    if verificar_salida and listo:
        r.update(verificar(puerto))
    return r
