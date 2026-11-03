"""Pasarela de privacidad del PC: VPN + Tor con UN control, dos objetivos y vigilancia automática.

Modos (excluyentes: cada cambio apaga primero lo que sobra, así nunca hay dos cosas en conflicto):
    apagado · proton · tor · proton_tor  (Tor sobre VPN: el operador ve la VPN; la VPN ve Tor cifrado;
                                          el sitio ve un relevo Tor. Nadie ve a la vez quién y qué.)
    («proton» es el nombre histórico del modo VPN: sirve para ProtonVPN y para el servidor propio.)
Objetivos (velocidad y anonimato se contraponen; lo decide la persona):
    velocidad — la ubicación con menor latencia medida; Tor reutiliza circuitos; la salida a internet
                de GuardianIA va por Tor si está activo («preferir»).
    anonimato — ubicaciones fuera de 5/9/14 Ojos y de su país; Tor aísla cada sitio en un circuito
                distinto; red en modo ESTRICTO (si Tor cae, GuardianIA no sale a internet).

Vigilante: cada 30 s revisa el túnel y Tor; si algo cae, reconecta o cambia de ubicación solo.
Nunca se deja encendido un túnel que no cambió la IP («protección» falsa).
"""
from __future__ import annotations

import logging
import re
import subprocess
import threading
import time

from app.ai import anonimato
from app.core import red
from app.local import ajustes, sistema, tor
from app.local import vpn

log = logging.getLogger("guardian.privacidad")

MODOS = ("apagado", "proton", "tor", "proton_tor")
OBJETIVOS = ("velocidad", "anonimato")
_lock = threading.RLock()
_vigilante: threading.Thread | None = None
_ultimo = {"evento": "", "cuando": 0.0, "razon": ""}


class ErrorPrivacidad(Exception):
    pass


def usa_proton(m: str) -> bool:
    return m in ("proton", "proton_tor")


def usa_tor(m: str) -> bool:
    return m in ("tor", "proton_tor")


def config() -> dict:
    a = ajustes.leer()
    return {"modo": a.get("privacidad_modo", "apagado") if a.get("privacidad_modo") in MODOS else "apagado",
            "objetivo": a.get("privacidad_objetivo", "anonimato") if a.get("privacidad_objetivo") in OBJETIVOS else "anonimato",
            "pais": a.get("privacidad_pais") if a.get("privacidad_pais") in vpn.PAISES_VPN else None}


# ─────────────────────────── elegir la ubicación
def latencia_ms(host: str, timeout_ms: int = 1500) -> int | None:
    """Ping ICMP al servidor (WireGuard usa UDP y no responde sin clave). Sin shell: lista de argumentos."""
    if not re.fullmatch(r"[A-Za-z0-9.\-:]{1,253}", host or ""):
        return None
    cmd = (["ping", "-n", "1", "-w", str(timeout_ms), host] if sistema.ES_WINDOWS
           else ["ping", "-c", "1", "-W", str(max(1, timeout_ms // 1000)), host])
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_ms / 1000 + 2,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if sistema.ES_WINDOWS else 0)
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r"(?:time|tiempo)[=<]\s*([\d.,]+)\s*ms", r.stdout or "", re.I)
    return round(float(m.group(1).replace(",", "."))) if m else None


def ordenar(ubic: list[dict], objetivo: str, pais_usuario: str | None, pais_destino: str | None,
            latencias: dict[str, int | None] | None = None) -> list[dict]:
    """Mejor primero. Con país pedido manda el país; si no: velocidad = latencia; anonimato = jurisdicción.
    El servidor propio de GuardianIA va primero salvo que se pida otro país (es suyo y sin registros)."""
    latencias = latencias or {}

    def puntaje(u: dict) -> float:
        pais = u.get("pais")
        propio = 50 if u.get("propio") else 0
        if pais_destino:
            return (1000 if pais == pais_destino else 0) + (10 if u.get("verificado") else 0) + propio / 10
        if objetivo == "velocidad":
            lat = latencias.get(u["id"])
            return ((10_000 - lat) if lat is not None else 0) + propio
        jur = anonimato.jurisdiccion(pais)["puntaje"]
        return jur * 80 + (15 if pais and pais != pais_usuario else 0) + (5 if u.get("verificado") else 0) + propio
    return sorted(ubic, key=puntaje, reverse=True)


def razon(u: dict, objetivo: str, pais_destino: str | None, lat: int | None) -> str:
    nombre = u.get("pais_nombre") or "esa ubicación"
    if u.get("propio") and not pais_destino:
        return f"Su propio servidor GuardianIA VPN ({nombre}): sin registros y sin depender de terceros."
    if pais_destino:
        return f"Sale desde {nombre}: los sitios le ven en ese país."
    if objetivo == "velocidad":
        return f"{nombre}: la menor latencia medida ({lat} ms)." if lat is not None else f"{nombre}: disponible."
    j = anonimato.jurisdiccion(u.get("pais"))
    return (f"{nombre} no pertenece a las alianzas de vigilancia 5/9/14 Ojos." if j["alianza"] == "ninguna"
            else f"{nombre} pertenece a los {j['alianza']}: aceptable, no ideal.")


def _conectar_vpn(objetivo: str, pais_destino: str | None) -> dict:
    ubic = vpn.ubicaciones()
    if not ubic:
        if vpn.perfil():
            return {"estado": vpn.conectar(), "razon": "Usando la configuración importada."}
        raise ErrorPrivacidad("Primero conecte su servidor GuardianIA VPN o agregue ubicaciones de ProtonVPN.")
    lats = ({u["id"]: latencia_ms((u.get("endpoint") or "").rsplit(":", 1)[0]) for u in ubic}
            if objetivo == "velocidad" and not pais_destino else {})
    orden = ordenar(ubic, objetivo, _pais_usuario(), pais_destino, lats)
    ultimo_error = ""
    for i, u in enumerate(orden[:3]):
        try:
            vpn.usar_ubicacion(u["id"])
            e = vpn.conectar()
            if e.get("verificado"):
                return {"estado": e, "ubicacion": u, "intentos": i + 1,
                        "razon": razon(u, objetivo, pais_destino, lats.get(u["id"]))}
            ultimo_error = f"con {u.get('pais_nombre')} la IP no cambió"
        except vpn.ErrorVpn as ex:
            ultimo_error = str(ex)
    try:
        vpn.desconectar()                           # nunca un túnel encendido que no protege
    except vpn.ErrorVpn:
        pass
    raise ErrorPrivacidad(f"Probé {min(3, len(orden))} ubicación(es) y ninguna protegió la conexión ({ultimo_error}).")


def _pais_usuario() -> str | None:
    return (vpn._estado_archivo() or {}).get("pais_previo") or "CO"


# ─────────────────────────── activar / apagar
def activar(modo: str, objetivo: str = "anonimato", pais: str | None = None) -> dict:
    if modo not in MODOS or objetivo not in OBJETIVOS:
        raise ErrorPrivacidad("Modo u objetivo inválido")
    if pais is not None and pais not in vpn.PAISES_VPN:
        raise ErrorPrivacidad(f"País no disponible: {pais}")
    with _lock:
        ajustes.guardar({"privacidad_modo": modo, "privacidad_objetivo": objetivo, "privacidad_pais": pais,
                         "tor_aislamiento": objetivo == "anonimato"})
        # 1) Apagar lo que sobra.
        if not usa_tor(modo):
            tor.detener()
            red.fijar_modo("apagado")
        if not usa_proton(modo):
            try:
                vpn.desconectar()
            except vpn.ErrorVpn:
                pass
        resultado: dict = {"modo": modo, "objetivo": objetivo}
        # 2) La VPN primero: así Tor arranca YA dentro del túnel (Tor sobre VPN).
        if usa_proton(modo):
            resultado["vpn"] = _conectar_vpn(objetivo, pais)
        if usa_tor(modo):
            # El aislamiento por sitio se aplica al arrancar: si Tor ya corría, se reinicia.
            tor.detener()
            if pais and pais in tor.PAISES and modo == "tor":
                tor.fijar_pais(pais)
            tor.iniciar()
            red.fijar_modo("estricto" if objetivo == "anonimato" else "preferir")
        _anotar("activado", resultado.get("vpn", {}).get("razon", ""))
        _asegurar_vigilante()
        return {**estado(), "razon": resultado.get("vpn", {}).get("razon", "")}


def nueva_identidad() -> dict:
    """Circuitos Tor nuevos: los sitios verán otra IP (Tor limita esto a una vez cada ~10 s)."""
    from app.local import tor_control
    try:
        c = tor_control.ControlTor(tor.cookie_control())
    except (OSError, tor_control.ErrorControl) as e:
        raise ErrorPrivacidad("Tor no está activo (o no lo lanzó GuardianIA).") from e
    try:
        c.nueva_identidad()
    finally:
        c.cerrar()
    _anotar("nueva_identidad", "Circuitos Tor renovados")
    return estado()


# ─────────────────────────── estado en vivo
def _circuito() -> dict:
    from app.local import tor_control
    try:
        c = tor_control.ControlTor(tor.cookie_control(), timeout=2.0)
    except (OSError, tor_control.ErrorControl):
        return {}
    try:
        return {"arranque": c.arranque(), "relevos": c.circuito_actual()}
    except (OSError, tor_control.ErrorControl):
        return {}
    finally:
        c.cerrar()


def estado() -> dict:
    cfg = config()
    t = tor.estado()
    vpn_arriba = vpn.activo() if vpn.wireguard() else False
    est_vpn = vpn.estadisticas(medir_latencia=False, leer_bytes=True) if vpn_arriba else {}
    circuito = _circuito() if t.get("activo") else {}
    relevos = circuito.get("relevos") or []
    salida_tor = relevos[-1] if relevos else {}
    protegido = (cfg["modo"] != "apagado" and (not usa_proton(cfg["modo"]) or vpn_arriba)
                 and (not usa_tor(cfg["modo"]) or bool(t.get("listo"))))
    if usa_tor(cfg["modo"]):
        cal = anonimato.evaluar(ip_oculta=bool(t.get("listo")), ipv6_fuga=None, dns_fuga=False, kill_switch=None,
                                proveedor="tor", nombre="Tor", pais_salida=salida_tor.get("pais"))
    elif usa_proton(cfg["modo"]):
        cal = vpn.anonimato()
    else:
        cal = None
    ubic_en_uso = next((u for u in vpn.ubicaciones() if u["en_uso"]), None) if usa_proton(cfg["modo"]) else None
    return {**cfg, "protegido": protegido, "vpn_activa": vpn_arriba,
            "ubicacion": ubic_en_uso, "vpn": est_vpn,
            "tor": {"activo": t.get("activo"), "listo": t.get("listo"), "progreso": t.get("progreso"),
                    "resumen": t.get("resumen"), "error": t.get("error"), "circuito": relevos,
                    "aislamiento": tor.aislamiento()},
            "red": red.estado(), "anonimato": cal, "ultimo": dict(_ultimo)}


# ─────────────────────────── vigilante
def _anotar(evento: str, razon_: str) -> None:
    _ultimo.update(evento=evento, cuando=time.time(), razon=razon_)


def revisar_una_vez() -> str | None:
    """Revisa y repara. Devuelve qué hizo (para el registro y las pruebas) o None si todo estaba bien."""
    cfg = config()
    if cfg["modo"] == "apagado":
        return None
    with _lock:
        if usa_proton(cfg["modo"]) and vpn.wireguard() and not vpn.activo():
            try:
                _conectar_vpn(cfg["objetivo"], cfg["pais"])
                _anotar("reconectado", "El túnel se había caído: reconectado.")
                return "reconectado"
            except ErrorPrivacidad as e:
                _anotar("fallo", str(e))
                return "fallo"
        if usa_tor(cfg["modo"]) and not tor.estado().get("activo"):
            try:
                tor.iniciar()
                _anotar("tor_reiniciado", "Tor se había detenido: reiniciado.")
                return "tor_reiniciado"
            except tor.ErrorTor as e:
                _anotar("fallo", str(e))
                return "fallo"
    return None


def _asegurar_vigilante() -> None:
    global _vigilante
    if _vigilante and _vigilante.is_alive():
        return

    def bucle():
        while config()["modo"] != "apagado":
            time.sleep(30)
            try:
                hecho = revisar_una_vez()
                if hecho:
                    log.info("privacidad: %s", hecho)
            except Exception:  # noqa: BLE001 - el vigilante nunca debe morir por un error puntual
                log.exception("vigilante de privacidad")

    if config()["modo"] != "apagado":
        _vigilante = threading.Thread(target=bucle, name="vigilante-privacidad", daemon=True)
        _vigilante.start()
