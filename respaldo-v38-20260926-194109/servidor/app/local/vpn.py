"""La VPN de este equipo, levantada por el propio servidor (sin agente, sin PowerShell).

**Por qué no conectaba.** Los seis servidores de la lista (Bogotá, México, São Paulo…) son de
ejemplo: en la base de datos no tienen clave pública ni endpoint. No hay ningún WireGuard real detrás,
y la sesión quedaba eterna en «conectando» esperando a un agente que nunca podía levantar nada. Su
prueba de dnsleaktest lo confirmó: IP y DNS de Claro (Telmex) en Colombia.

**Qué hace ahora.** Tres orígenes de túnel, los tres WireGuard real:

1. **Cloudflare WARP (gratis).** Se registra una cuenta anónima en la API de WARP con una clave
   generada aquí; Cloudflare devuelve la configuración. Es lo que hace la herramienta wgcf.
2. **Un archivo .conf importado**, por ejemplo el que da gratis ProtonVPN o cualquier proveedor
   compatible con WireGuard.
3. **Su propio servidor**, registrado con `python -m app.cli vpn-servidor` (ver docs/VPN_SERVIDOR.md).

El túnel lo instala wireguard.exe como servicio de Windows. Eso requiere administrador, así que se
pide con UNA ventana de control de cuentas en el momento de conectar (no hace falta correr el
servidor como administrador, que sería mucho más riesgoso).

**Nada se marca como conectado sin prueba**: se consulta https://www.cloudflare.com/cdn-cgi/trace
antes y después. Conectado = la IP pública cambió (o Cloudflare confirma warp=on).
"""
from __future__ import annotations

import base64
import json
import os
import re
import shutil
import stat
import threading
import time
import uuid
import socket
from datetime import datetime, timezone
from pathlib import Path

import httpx
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, NoEncryption, PrivateFormat, PublicFormat

from app.local import ajustes, sistema

NOMBRE = "guardiania"
SERVICIO = f"WireGuardTunnel${NOMBRE}"
WARP_API = "https://api.cloudflareclient.com/v0a2158/reg"
WARP_PEER = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="   # clave pública fija de Cloudflare WARP
WARP_ENDPOINT = "engage.cloudflareclient.com:2408"
TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
_CLAVE_WG = re.compile(r"^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$")
_PROHIBIDAS = {"preup", "postup", "predown", "postdown"}   # ejecutan comandos: nunca desde un .conf ajeno
_lock = threading.Lock()


class ErrorVpn(Exception):
    pass


def _dir() -> Path:
    return ajustes.sub("vpn")


def _escribir_privado(ruta: Path, texto: str) -> None:
    tmp = ruta.with_suffix(ruta.suffix + ".tmp")
    tmp.write_text(texto, "utf-8")
    try:
        os.chmod(tmp, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
    os.replace(tmp, ruta)


# ════════════════════════════════════════════════════════════ claves
def claves() -> tuple[str, str]:
    """(privada, pública) de ESTE equipo. La privada nunca sale de aquí."""
    archivo = _dir() / "claves.json"
    if archivo.exists():
        datos = json.loads(archivo.read_text("utf-8"))
        return datos["privada"], datos["publica"]
    priv = X25519PrivateKey.generate()
    privada = base64.b64encode(priv.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption())).decode()
    publica = base64.b64encode(priv.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    _escribir_privado(archivo, json.dumps({"privada": privada, "publica": publica}))
    return privada, publica


# ══════════════════════════════════════════════════════ perfil (qué túnel)
def perfil() -> dict | None:
    try:
        return json.loads((_dir() / "perfil.json").read_text("utf-8"))
    except (OSError, ValueError):
        return None


def _guardar_perfil(p: dict) -> dict:
    _escribir_privado(_dir() / "perfil.json", json.dumps(p, ensure_ascii=False, indent=1))
    return p


def _publico(p: dict | None) -> dict | None:
    """Lo que se puede mostrar en pantalla: nunca claves privadas."""
    if not p:
        return None
    return {"proveedor": p["proveedor"], "nombre": p["nombre"], "endpoint": p["peer"].get("Endpoint"),
            "direccion": p["interfaz"].get("Address"), "dns": p["interfaz"].get("DNS"),
            "creado": p.get("creado"), "clave_publica_equipo": p.get("clave_publica_equipo")}


def importar_conf(texto: str, nombre: str | None = None) -> dict:
    """Valida y guarda un .conf de WireGuard de otro proveedor."""
    if len(texto) > 20_000:
        raise ErrorVpn("El archivo es demasiado grande para ser una configuración de WireGuard")
    seccion, interfaz, peer = None, {}, {}
    for linea in texto.splitlines():
        linea = linea.split("#", 1)[0].strip()
        if not linea:
            continue
        if linea.startswith("["):
            seccion = linea.strip("[] ").lower()
            if seccion == "peer" and peer:
                continue   # solo el primer peer: una VPN de salida tiene uno
            continue
        if "=" not in linea:
            raise ErrorVpn(f"Línea no válida: «{linea[:40]}»")
        clave, valor = (x.strip() for x in linea.split("=", 1))
        if clave.lower() in _PROHIBIDAS:
            raise ErrorVpn(f"El archivo trae «{clave}», que ejecuta comandos en su equipo. Por seguridad no se "
                           "acepta: quítelo y vuelva a importarlo.")
        destino = interfaz if seccion == "interface" else peer if seccion == "peer" else None
        if destino is None:
            raise ErrorVpn("El archivo no tiene las secciones [Interface] y [Peer]")
        destino[clave] = valor
    for requerido, donde in (("PrivateKey", interfaz), ("Address", interfaz), ("PublicKey", peer),
                             ("Endpoint", peer)):
        if not donde.get(requerido):
            raise ErrorVpn(f"Falta «{requerido}» en el archivo")
    if not _CLAVE_WG.match(interfaz["PrivateKey"]) or not _CLAVE_WG.match(peer["PublicKey"]):
        raise ErrorVpn("Las claves del archivo no tienen el formato de WireGuard")
    peer.setdefault("AllowedIPs", "0.0.0.0/0, ::/0")
    if "0.0.0.0/0" not in peer["AllowedIPs"]:
        raise ErrorVpn("El archivo no enruta todo el tráfico (AllowedIPs sin 0.0.0.0/0): su IP no cambiaría")
    peer.setdefault("PersistentKeepalive", "25")
    from app.ai import anonimato
    endurecido = anonimato.endurecer(interfaz, peer)        # DNS privado + IPv6 por el túnel
    privada = interfaz.pop("PrivateKey")
    _escribir_privado(_dir() / "importada.key", privada)
    publica = base64.b64encode(
        X25519PrivateKey.from_private_bytes(base64.b64decode(privada)).public_key()
        .public_bytes(Encoding.Raw, PublicFormat.Raw)).decode()
    p = _guardar_perfil({"proveedor": "importado", "nombre": (nombre or "VPN importada")[:60],
                         "creado": time.time(), "clave_publica_equipo": publica, "privada_propia": False,
                         "interfaz": interfaz, "peer": peer, "endurecido": endurecido,
                         "pais": pais_desde_nombre(nombre or ""), "id": uuid.uuid4().hex[:8]})
    _guardar_ubicacion(p, privada)
    return p


# ════════════════════════════════════ ubicaciones (varios .conf, uno por país)
# Nombres que ProtonVPN y otros usan en sus .conf: «wg-US-FREE-12», «JP-FREE#3», «ProtonVPN CH-1».
PAISES_VPN = {
    "US": "Estados Unidos", "CA": "Canadá", "MX": "México", "BR": "Brasil", "AR": "Argentina", "CL": "Chile",
    "CO": "Colombia", "PE": "Perú", "ES": "España", "GB": "Reino Unido", "UK": "Reino Unido", "FR": "Francia",
    "DE": "Alemania", "NL": "Países Bajos", "BE": "Bélgica", "CH": "Suiza", "SE": "Suecia", "NO": "Noruega",
    "FI": "Finlandia", "DK": "Dinamarca", "IT": "Italia", "PL": "Polonia", "RO": "Rumania", "AT": "Austria",
    "IE": "Irlanda", "PT": "Portugal", "CZ": "Chequia", "IS": "Islandia", "JP": "Japón", "KR": "Corea del Sur",
    "SG": "Singapur", "HK": "Hong Kong", "TW": "Taiwán", "IN": "India", "AU": "Australia", "NZ": "Nueva Zelanda",
    "ZA": "Sudáfrica", "IL": "Israel", "TR": "Turquía", "AE": "Emiratos Árabes",
}
_ID_UBICACION = re.compile(r"^[0-9a-f]{8}$")      # evita rutas como «../../» en las operaciones de archivo


def pais_desde_nombre(nombre: str) -> str | None:
    """Detecta el país en el nombre del archivo («wg-US-FREE-12» → US). None si no hay uno claro."""
    tokens = re.split(r"[^A-Za-z]+", nombre or "")
    hallados = [t.upper() for t in tokens if len(t) == 2 and t.upper() in PAISES_VPN]
    if not hallados:
        return None
    pais = hallados[0]
    return "GB" if pais == "UK" else pais


def _dir_ubicaciones() -> Path:
    d = _dir() / "ubicaciones"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _guardar_ubicacion(p: dict, privada: str) -> None:
    carpeta = _dir_ubicaciones() / p["id"]
    carpeta.mkdir(parents=True, exist_ok=True)
    _escribir_privado(carpeta / "perfil.json", json.dumps(p, ensure_ascii=False, indent=1))
    _escribir_privado(carpeta / "privada.key", privada)


def _ubicacion(id_: str) -> tuple[dict, str]:
    if not _ID_UBICACION.fullmatch(id_ or ""):
        raise ErrorVpn("Ubicación no válida")
    carpeta = _dir_ubicaciones() / id_
    try:
        return (json.loads((carpeta / "perfil.json").read_text("utf-8")),
                (carpeta / "privada.key").read_text("utf-8").strip())
    except (OSError, ValueError):
        raise ErrorVpn("Esa ubicación ya no existe")


def ubicaciones() -> list[dict]:
    """Ubicaciones guardadas (sin claves privadas), con cuál está en uso."""
    actual = (perfil() or {}).get("id")
    salida = []
    for carpeta in sorted(_dir_ubicaciones().iterdir()) if _dir_ubicaciones().exists() else []:
        if not _ID_UBICACION.fullmatch(carpeta.name):
            continue
        try:
            p = json.loads((carpeta / "perfil.json").read_text("utf-8"))
        except (OSError, ValueError):
            continue
        pais = p.get("pais_verificado") or p.get("pais")
        salida.append({"id": carpeta.name, "nombre": p.get("nombre"), "pais": pais,
                       "pais_nombre": PAISES_VPN.get(pais or "", "País desconocido"),
                       "verificado": bool(p.get("pais_verificado")), "endpoint": p["peer"].get("Endpoint"),
                       "en_uso": carpeta.name == actual, "propio": bool(p.get("propio"))})
    # Su servidor GuardianIA VPN primero; luego por país.
    return sorted(salida, key=lambda u: (not u["propio"], u["pais_nombre"], u["nombre"] or ""))


def usar_ubicacion(id_: str) -> dict:
    """Deja esa ubicación como túnel activo. Si la VPN está conectada, reconecta desde ahí."""
    p, privada = _ubicacion(id_)
    _escribir_privado(_dir() / "importada.key", privada)
    _guardar_perfil(p)
    return conectar() if (wireguard() and activo()) else {"perfil": _publico(p), "reconectar": False}


def importar_de_carpeta(carpeta: Path | None = None, maximo: int = 40) -> dict:
    """Importa de una vez los .conf de WireGuard de una carpeta (por defecto, Descargas).

    Solo lee archivos «*.conf» pequeños de ESA carpeta (sin subcarpetas, sin seguir accesos directos),
    los valida uno por uno con las mismas reglas que la importación manual (rechaza PostUp/PreUp,
    claves inválidas, rutas parciales) y no duplica los que ya estaban (misma clave pública del
    servidor + mismo endpoint)."""
    carpeta = carpeta or (Path.home() / "Downloads")
    if not carpeta.is_dir():
        raise ErrorVpn(f"No existe la carpeta {carpeta}")
    ya = {(u.get("endpoint"), _ubicacion(u["id"])[0]["peer"].get("PublicKey")) for u in ubicaciones()}
    importadas, repetidas, rechazadas = [], 0, []
    for archivo in sorted(carpeta.glob("*.conf"))[:maximo]:
        try:
            if archivo.is_symlink() or not archivo.is_file() or archivo.stat().st_size > 20_000:
                continue
            texto = archivo.read_text("utf-8", errors="replace")
            if "[Interface]" not in texto or "[Peer]" not in texto:
                continue                                  # no es de WireGuard: se ignora en silencio
            clave = re.search(r"PublicKey\s*=\s*(\S+)", texto)
            ep = re.search(r"Endpoint\s*=\s*(\S+)", texto)
            if clave and ep and (ep.group(1), clave.group(1)) in ya:
                repetidas += 1
                continue
            p = importar_conf(texto, archivo.stem)
            ya.add((p["peer"].get("Endpoint"), p["peer"].get("PublicKey")))
            importadas.append({"nombre": p["nombre"], "pais": p.get("pais")})
        except ErrorVpn as e:
            rechazadas.append({"archivo": archivo.name, "motivo": str(e)})
    return {"carpeta": str(carpeta), "importadas": importadas, "repetidas": repetidas, "rechazadas": rechazadas}


def eliminar_ubicacion(id_: str) -> None:
    _ubicacion(id_)                                   # valida el id y que exista
    shutil.rmtree(_dir_ubicaciones() / id_, ignore_errors=True)


def _confirmar_pais_ubicacion(pais_real: str | None) -> None:
    """Tras conectar, guarda el país que de verdad ven los sitios (puede diferir del nombre del archivo)."""
    p = perfil() or {}
    if not pais_real or not p.get("id") or not _ID_UBICACION.fullmatch(p["id"]):
        return
    try:
        guardada, privada = _ubicacion(p["id"])
    except ErrorVpn:
        return
    guardada["pais_verificado"] = pais_real.upper()
    _guardar_ubicacion(guardada, privada)


def configurar_servidor(servidor, direccion_tunel: str) -> dict:
    """Perfil para un servidor propio registrado en la base (VpnServer con clave y endpoint)."""
    _, publica = claves()
    return _guardar_perfil({
        "proveedor": "servidor", "nombre": f"{servidor.city}, {servidor.country}", "servidor_id": servidor.id,
        "creado": time.time(), "clave_publica_equipo": publica, "privada_propia": True,
        "interfaz": {"Address": direccion_tunel, "DNS": servidor.dns},
        "peer": {"PublicKey": servidor.public_key, "Endpoint": servidor.endpoint,
                 "AllowedIPs": "0.0.0.0/0, ::/0", "PersistentKeepalive": "25"},
    })


def _redes_publicas_v4() -> str:
    """Todo IPv4 menos redes locales, loopback y multicast (igual que la app Android)."""
    import ipaddress
    todo = [ipaddress.ip_network("0.0.0.0/0")]
    for ex in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8", "224.0.0.0/4",
               "255.255.255.255/32"):
        e = ipaddress.ip_network(ex)
        nuevo = []
        for n in todo:
            if e.subnet_of(n):
                nuevo += list(n.address_exclude(e))
            elif not n.subnet_of(e):
                nuevo.append(n)
        todo = nuevo
    return ", ".join(str(n) for n in ipaddress.collapse_addresses(todo))


def permitir_lan() -> bool:
    """¿La VPN deja pasar la red de la casa? (por defecto sí: el celular necesita llegar a este PC)."""
    return bool(ajustes.leer().get("vpn_permitir_lan", True))


def rutas_para(allowed: str, lan: bool) -> str:
    """Con la red local permitida, 0.0.0.0/0 → IPv4 públicas y ::/0 → IPv6 pública (2000::/3).

    Sin ninguna ruta «/0», WireGuard para Windows NO activa su bloqueo de tráfico fuera del túnel,
    que también cortaba la red local: el celular dejaba de ver el PC con la VPN encendida.
    La IP pública sigue oculta y el IPv6 público sigue por el túnel (sin fugas)."""
    if not lan:
        return allowed
    rutas = [r.strip() for r in allowed.split(",") if r.strip()]
    salida = []
    for r in rutas:
        if r == "0.0.0.0/0":
            salida.append(_redes_publicas_v4())
        elif r == "::/0":
            salida.append("2000::/3")
        else:
            salida.append(r)
    return ", ".join(salida)


def _texto_conf(p: dict) -> str:
    privada = claves()[0] if p.get("privada_propia", True) else (_dir() / "importada.key").read_text("utf-8").strip()
    lineas = ["[Interface]", f"PrivateKey = {privada}"]
    lineas += [f"{k} = {v}" for k, v in p["interfaz"].items() if k.lower() != "privatekey"]
    lan = permitir_lan()
    lineas += ["", "[Peer]"] + [f"{k} = {rutas_para(v, lan) if k.lower() == 'allowedips' else v}"
                                for k, v in p["peer"].items()]
    return "\n".join(lineas) + "\n"


# ═══════════════════════════════════════════════════════════ túnel
def wireguard() -> str | None:
    """wireguard.exe en Windows (no agrega su carpeta al PATH), wg-quick en Linux/macOS."""
    if sistema.ES_WINDOWS:
        for base in (os.environ.get("ProgramFiles", r"C:\Program Files"), r"C:\Program Files (x86)"):
            candidato = Path(base) / "WireGuard" / "wireguard.exe"
            if candidato.is_file():
                return str(candidato)
        return shutil.which("wireguard")
    return shutil.which("wg-quick")


def activo() -> bool:
    """¿Hay un túnel de GuardianIA arriba? En Windows se comprueba de dos formas por robustez."""
    if sistema.ES_WINDOWS:
        if sistema.estado_servicio(SERVICIO) == "RUNNING":
            return True
        # Respaldo: `wg show` lista las interfaces activas; si aparece la nuestra, está arriba.
        return NOMBRE in _wg_interfaces()
    return Path(f"/sys/class/net/{NOMBRE}").exists()


def tunel_instalado() -> bool:
    """¿Existe el servicio del túnel (corriendo o detenido)? En Windows, sc.exe no lo encuentra si no existe."""
    if not sistema.ES_WINDOWS:
        return activo()
    return sistema.estado_servicio(SERVICIO) is not None or NOMBRE in _wg_interfaces()


def _wg_interfaces() -> set[str]:
    import shutil as _sh
    import subprocess
    wg = _sh.which("wg")
    if not wg and sistema.ES_WINDOWS:
        cand = Path(r"C:\Program Files\WireGuard\wg.exe")
        wg = str(cand) if cand.exists() else None
    if not wg:
        return set()
    try:
        r = subprocess.run([wg, "show", "interfaces"], capture_output=True, text=True, timeout=6,
                           creationflags=sistema.CREATE_NO_WINDOW if sistema.ES_WINDOWS else 0)
        return set((r.stdout or "").split())
    except (OSError, subprocess.SubprocessError):
        return set()


def trace(timeout: float = 6.0) -> dict:
    """IP pública y país vistos desde internet. Cloudflare además dice si el tráfico va por WARP."""
    try:
        r = httpx.get(TRACE, timeout=timeout, headers={"Cache-Control": "no-cache"})
        datos = dict(linea.split("=", 1) for linea in r.text.splitlines() if "=" in linea)
        return {"ip": datos.get("ip"), "pais": datos.get("loc"), "warp": datos.get("warp"),
                "colo": datos.get("colo")}
    except (httpx.HTTPError, ValueError):
        pass
    try:
        ip = httpx.get("https://api.ipify.org", timeout=timeout).text.strip()
        return {"ip": ip, "pais": None, "warp": None, "colo": None}
    except httpx.HTTPError:
        return {"ip": None, "pais": None, "warp": None, "colo": None}


def ipv6_publica(timeout: float = 4.0) -> str | None:
    """IPv6 pública vista desde internet, o None si no hay salida IPv6 (lo normal con el túnel endurecido)."""
    try:
        ip = httpx.get("https://api6.ipify.org", timeout=timeout).text.strip()
        return ip if ":" in ip else None
    except httpx.HTTPError:
        return None


def resolutor_dns() -> str | None:
    """IP del resolutor DNS que atiende a este equipo (whoami.akamai.net responde con esa IP).

    Si después de conectar sigue siendo el mismo que sin VPN, las consultas no van por el túnel.
    """
    try:
        return socket.gethostbyname("whoami.akamai.net")
    except OSError:
        return None


def sondas() -> dict:
    """Lo que ve internet de este equipo: IPv4, IPv6 y resolutor DNS."""
    t = trace()
    return {"ip": t.get("ip"), "pais": t.get("pais"), "warp": t.get("warp"), "colo": t.get("colo"),
            "ip6": ipv6_publica(), "dns": resolutor_dns()}


def fugas(antes: dict, despues: dict) -> dict:
    """Compara antes y después de conectar. None = no se pudo comprobar."""
    ip6_fuga = None
    if despues.get("ip6") and antes.get("ip6"):
        ip6_fuga = despues["ip6"] == antes["ip6"]
    elif despues.get("ip6") is None:
        ip6_fuga = None                      # sin salida IPv6: no hay por dónde fugarse
    dns_fuga = None
    if despues.get("dns") and antes.get("dns"):
        dns_fuga = despues["dns"] == antes["dns"]
    return {"ipv6_fuga": ip6_fuga, "dns_fuga": dns_fuga}


def _estado_archivo() -> dict:
    try:
        return json.loads((_dir() / "estado.json").read_text("utf-8"))
    except (OSError, ValueError):
        return {}


def _guardar_estado(e: dict) -> dict:
    _escribir_privado(_dir() / "estado.json", json.dumps(e))
    return e


def estado() -> dict:
    wg = wireguard()
    arriba = activo() if wg else False
    e = _estado_archivo()
    return {"wireguard": wg, "wireguard_instalado": bool(wg), "sistema": "windows" if sistema.ES_WINDOWS else "otro",
            "administrador": sistema.es_admin(), "perfil": _publico(perfil()), "activo": arriba,
            "conectado_desde": e.get("desde") if arriba else None, "ip_previa": e.get("ip_previa"),
            "ip_salida": e.get("ip_salida") if arriba else None, "pais_salida": e.get("pais") if arriba else None,
            "verificado": bool(e.get("verificado")) if arriba else False, "warp": e.get("warp") if arriba else None,
            "sesion_id": e.get("sesion_id") if arriba else None}


def conectar() -> dict:
    with _lock:
        p = perfil()
        if not p:
            raise ErrorVpn("Primero importe una ubicación de ProtonVPN (archivo .conf) o active el modo Tor")
        wg = wireguard()
        if not wg:
            raise ErrorVpn("WireGuard no está instalado. Descárguelo gratis de https://www.wireguard.com/install/ "
                           "(es un instalador normal, sin comandos) y vuelva a intentar.")
        antes = sondas()
        conf = _dir() / f"{NOMBRE}.conf"
        _escribir_privado(conf, _texto_conf(p))
        try:
            if sistema.ES_WINDOWS:
                # Se reinstala limpio SOLO si ya existe un túnel: desinstalar uno inexistente hacía que
                # WireGuard mostrara «El servicio especificado no existe…», pedía un segundo permiso de
                # administrador y el primer intento fallaba.
                if tunel_instalado():
                    sistema.ejecutar_elevado(wg, ["/uninstalltunnelservice", NOMBRE])
                    time.sleep(1.5)
                codigo = sistema.ejecutar_elevado(wg, ["/installtunnelservice", str(conf)])
                if codigo != 0 and activo():
                    # Quedó corriendo pese al código: es el mismo túnel. Se acepta y se verifica.
                    codigo = 0
            else:
                codigo = sistema.ejecutar_elevado("sudo", ["-n", wg, "up", str(conf)])
        except sistema.ElevacionCancelada as e:
            raise ErrorVpn("Canceló el permiso de administrador: sin él Windows no deja crear el túnel") from e
        except (OSError, Exception) as e:  # noqa: BLE001
            if "already" in str(e).lower() and activo():
                codigo = 0   # el túnel ya estaba arriba: no es un error
            else:
                raise ErrorVpn(f"WireGuard no pudo iniciar el túnel: {e}") from e
        if codigo != 0:
            raise ErrorVpn(f"WireGuard terminó con el código {codigo}. Abra la app WireGuard para ver el detalle.")

        # Verificación: hasta 20 s para el primer apretón de manos y el cambio de IP.
        despues, verificado = {}, False
        for _ in range(10):
            time.sleep(2)
            despues = trace(timeout=4)
            if despues.get("ip") and (despues["ip"] != antes.get("ip") or despues.get("warp") in ("on", "plus")):
                verificado = True
                break
        # Pruebas de anonimato con el túnel ya arriba: IPv6 y DNS.
        despues_full = {**despues, "ip6": ipv6_publica(), "dns": resolutor_dns()} if verificado else despues
        f = fugas(antes, despues_full) if verificado else {"ipv6_fuga": None, "dns_fuga": None}
        if verificado:
            _confirmar_pais_ubicacion(despues.get("pais"))   # el país que de verdad ven los sitios
        if verificado:
            _confirmar_pais_ubicacion(despues.get("pais"))   # el país que de verdad ven los sitios
        # La IP real solo se guarda en ESTE equipo (para verificar el cambio); nunca se envía al servidor.
        return _guardar_estado({"desde": time.time(), "ip_previa": antes.get("ip"), "ip_salida": despues.get("ip"),
                                "pais": despues.get("pais"), "warp": despues.get("warp"), "colo": despues.get("colo"),
                                "verificado": verificado, "proveedor": p["proveedor"],
                                "ip6_previa": antes.get("ip6"), "dns_previo": antes.get("dns"),
                                "pais_previo": antes.get("pais"), **f})


def desconectar() -> dict:
    with _lock:
        wg = wireguard()
        if not wg:
            raise ErrorVpn("WireGuard no está instalado")
        try:
            if sistema.ES_WINDOWS:
                # Si no hay túnel instalado no hay nada que desinstalar (evita el error de WireGuard).
                codigo = sistema.ejecutar_elevado(wg, ["/uninstalltunnelservice", NOMBRE]) if tunel_instalado() else 0
            else:
                codigo = sistema.ejecutar_elevado("sudo", ["-n", wg, "down", str(_dir() / f"{NOMBRE}.conf")])
        except sistema.ElevacionCancelada as e:
            raise ErrorVpn("Canceló el permiso de administrador: el túnel sigue activo") from e
        previo = _estado_archivo()
        _guardar_estado({})
        return {"cerrado": codigo == 0, "codigo": codigo, "sesion_id": previo.get("sesion_id"),
                "desde": previo.get("desde")}


def verificar() -> dict:
    e = _estado_archivo()
    ahora = trace()
    arriba = activo()
    verificado = bool(arriba and ahora.get("ip") and (ahora["ip"] != e.get("ip_previa")
                                                       or ahora.get("warp") in ("on", "plus")))
    if arriba:
        _guardar_estado({**e, "ip_salida": ahora.get("ip"), "pais": ahora.get("pais"), "warp": ahora.get("warp"),
                         "colo": ahora.get("colo"), "verificado": verificado})
    return {"activo": arriba, "verificado": verificado, "ip": ahora.get("ip"), "pais": ahora.get("pais"),
            "warp": ahora.get("warp"), "ip_previa": e.get("ip_previa")}


def anonimato(repetir: bool = False) -> dict:
    """Nivel de anonimato del túnel actual. Con repetir=True vuelve a medir IPv6 y DNS."""
    from app.ai import anonimato as an
    e = _estado_archivo()
    p = perfil() or {}
    arriba = activo() if wireguard() else False
    if arriba and repetir:
        ahora = sondas()
        f = fugas({"ip6": e.get("ip6_previa"), "dns": e.get("dns_previo")}, ahora)
        e = _guardar_estado({**e, **f, "ip_salida": ahora.get("ip"), "pais": ahora.get("pais"),
                             "verificado": bool(ahora.get("ip") and ahora.get("ip") != e.get("ip_previa"))})
    rutas = (p.get("peer") or {}).get("AllowedIPs", "")
    # En Windows, WireGuard bloquea el tráfico fuera del túnel cuando el único par enruta 0.0.0.0/0 y ::/0.
    # Con la red local permitida (para que el celular llegue al PC) ese bloqueo no se activa.
    kill = (sistema.ES_WINDOWS and not permitir_lan() and "0.0.0.0/0" in rutas and "::/0" in rutas) if p else None
    r = an.evaluar(ip_oculta=bool(arriba and e.get("verificado")), ipv6_fuga=e.get("ipv6_fuga") if arriba else None,
                   dns_fuga=e.get("dns_fuga") if arriba else None, kill_switch=kill if kill else None,
                   proveedor=p.get("proveedor"), nombre=p.get("nombre"), pais_salida=e.get("pais") if arriba else None)
    return {**r, "activo": arriba, "endurecido": p.get("endurecido", [])}


# ════════════════════════════════════ catálogo de servidores conectables
# WARP no deja «elegir país»: enruta por la mejor ubicación de Cloudflare cerca de usted (desde
# Colombia, normalmente Bogotá). Por eso el catálogo distingue lo que SÍ es real:
#  · WARP: un clic, gratis, sin cuenta — la ubicación la decide Cloudflare (se muestra la real tras
#    conectar, leída de /cdn-cgi/trace).
#  · Importado: el .conf que usted bajó de Proton u otro proveedor — ahí sí elige el país.
# No se inventan servidores «disponibles» que no existen: eso era el error de la versión anterior.
COLOS = {
    "BOG": ("Bogotá", "CO"), "GRU": ("São Paulo", "BR"), "SCL": ("Santiago", "CL"), "LIM": ("Lima", "PE"),
    "MIA": ("Miami", "US"), "IAD": ("Washington", "US"), "LAX": ("Los Ángeles", "US"), "MAD": ("Madrid", "ES"),
    "AMS": ("Ámsterdam", "NL"), "FRA": ("Fráncfort", "DE"), "LHR": ("Londres", "GB"), "ZRH": ("Zúrich", "CH"),
    "ARN": ("Estocolmo", "SE"), "SIN": ("Singapur", "SG"), "NRT": ("Tokio", "JP"), "PTY": ("Panamá", "PA"),
}


def catalogo() -> list[dict]:
    """Ubicaciones que de verdad puede usar este equipo: sus .conf de ProtonVPN (una fila por país).
    (Cloudflare WARP se retiró en v3.5: una sola vía de VPN + Tor evita conflictos.)"""
    p = perfil()
    est = _estado_archivo()
    arriba = activo() if wireguard() else False
    filas: list[dict] = []
    for u in ubicaciones():
        filas.append({
            "id": u["id"], "proveedor": "importado", "nombre": u["nombre"], "conectable": True, "gratis": True,
            "un_clic": True, "city": (u.get("endpoint") or "—").split(":")[0], "country": u.get("pais") or "??",
            "elige_pais": True, "premium": False, "load": 0.3, "latency_ms": est.get("latencia_ms") or 0,
            "activo": arriba and u["en_uso"], "configurado": True, "ubicacion": True,
            "nota": f"{u['pais_nombre']}{' (confirmado)' if u['verificado'] else ''} · ProtonVPN",
        })
    if p and p.get("proveedor") == "importado" and not p.get("id"):
        pub = _publico(p)                         # .conf importado antes de v3.5 (sin biblioteca)
        filas.append({
            "id": "importado", "proveedor": "importado", "nombre": p.get("nombre", "VPN importada"),
            "conectable": True, "gratis": True, "un_clic": True, "city": pub.get("endpoint", "—").split(":")[0],
            "country": "??", "elige_pais": True, "premium": False, "load": 0.3,
            "latency_ms": est.get("latencia_ms") or 0, "activo": arriba, "configurado": True,
            "nota": "Su archivo .conf (por ejemplo, ProtonVPN gratis).",
        })
    return filas


# ═══════════════════════════════════════════ estadísticas en vivo del túnel
def _ruta_wg() -> str | None:
    wg = shutil.which("wg")
    if not wg and sistema.ES_WINDOWS:
        candidato = Path(r"C:\Program Files\WireGuard\wg.exe")
        wg = str(candidato) if candidato.exists() else None
    return wg


def _parse_dump(texto: str) -> dict:
    lineas = [l.split("\t") for l in (texto or "").strip().splitlines()]
    if len(lineas) < 2:
        return {}
    peer = lineas[1]   # la primera línea es la interfaz; la segunda, el peer
    try:
        return {"ultimo_handshake": int(peer[4]), "recibidos": int(peer[5]), "enviados": int(peer[6]),
                "endpoint": peer[2]}
    except (IndexError, ValueError):
        return {}


def _wg_show(permitir_elevacion: bool = False) -> dict:
    """Lee el estado real del túnel con `wg show` (no PowerShell): apretón de manos y bytes.

    Cuando WireGuard corre como servicio de Windows, `wg show … dump` necesita privilegios para leer
    los contadores. Si la salida directa viene vacía (sin admin), se reintenta de forma elevada
    escribiendo el volcado a un archivo temporal que luego se lee. Así los datos de Descargado/Subido
    aparecen aunque el servidor no corra como administrador.
    """
    import subprocess
    import tempfile
    wg = _ruta_wg()
    if not wg:
        return {}
    try:
        r = subprocess.run([wg, "show", NOMBRE, "dump"], capture_output=True, text=True, timeout=6,
                           creationflags=sistema.CREATE_NO_WINDOW if sistema.ES_WINDOWS else 0)
        datos = _parse_dump(r.stdout)
    except (OSError, subprocess.SubprocessError):
        datos = {}
    if datos or not sistema.ES_WINDOWS or not permitir_elevacion:
        return datos
    # Reintento elevado (solo bajo petición, no en cada refresco): wg.exe escribe el volcado a un
    # archivo que este proceso sí puede leer.
    try:
        salida = Path(tempfile.gettempdir()) / f"gia_wg_{NOMBRE}.txt"
        cmd = f'"{wg}" show {NOMBRE} dump > "{salida}"'
        codigo = sistema.ejecutar_elevado("cmd.exe", ["/c", cmd], timeout=8)
        if codigo == 0 and salida.exists():
            datos = _parse_dump(salida.read_text("utf-8", "ignore"))
            salida.unlink(missing_ok=True)
    except (OSError, sistema.ElevacionCancelada, Exception):  # noqa: BLE001
        pass
    return datos


def estadisticas(medir_latencia: bool = True, leer_bytes: bool = False) -> dict:
    """Cifras en vivo para la pantalla: tiempo conectado, datos, latencia y último apretón de manos.

    `leer_bytes=True` (al pulsar «Verificar») autoriza leer los contadores de forma elevada una vez;
    el refresco automático no lo hace, para no lanzar una ventana de permiso cada pocos segundos.
    """
    import time as _t
    e = _estado_archivo()
    arriba = activo()
    wg = _wg_show(permitir_elevacion=leer_bytes) if arriba else {}
    # Los bytes leídos se guardan para que el refresco automático los siga mostrando.
    if wg.get("recibidos") is not None:
        e["recibidos_bytes"], e["enviados_bytes"] = wg["recibidos"], wg["enviados"]
        _guardar_estado(e)
    elif arriba:
        wg.setdefault("recibidos", e.get("recibidos_bytes"))
        wg.setdefault("enviados", e.get("enviados_bytes"))
    latencia = None
    if arriba and medir_latencia:
        latencia = _latencia()
        if latencia is not None:
            e["latencia_ms"] = latencia
            _guardar_estado(e)
    ahora = _t.time()
    hs = wg.get("ultimo_handshake")
    return {
        "activo": arriba, "verificado": bool(e.get("verificado")) and arriba,
        "segundos_conectado": round(ahora - e["desde"]) if arriba and e.get("desde") else 0,
        "ip_salida": e.get("ip_salida"), "pais_salida": e.get("pais"), "warp": e.get("warp"),
        "colo": e.get("colo"), "ip_previa": e.get("ip_previa"),
        "recibidos_bytes": wg.get("recibidos"), "enviados_bytes": wg.get("enviados"),
        "latencia_ms": latencia if latencia is not None else e.get("latencia_ms"),
        "ultimo_handshake_hace_s": (round(ahora - hs) if hs else None),
        "handshake_ok": bool(hs and ahora - hs < 180),
        "proveedor": (perfil() or {}).get("proveedor"), "medido": ahora,
    }


def _latencia() -> int | None:
    """Latencia hasta Cloudflare a través del túnel (una petición HTTPS corta)."""
    import time as _t
    try:
        t0 = _t.perf_counter()
        httpx.head(TRACE, timeout=4)
        return round((_t.perf_counter() - t0) * 1000)
    except httpx.HTTPError:
        return None


def anotar_sesion(sesion_id: str) -> None:
    e = _estado_archivo()
    if e:
        _guardar_estado({**e, "sesion_id": sesion_id})
