"""Protección de este equipo: motor, escáner con progreso, cuarentena, seguridad de la API y VPN."""
import struct
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

EICAR = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"


@pytest.fixture(autouse=True)
def carpeta_local(tmp_path, monkeypatch):
    """Cada prueba con su propia carpeta de datos, firmas, caché y cuarentena."""
    monkeypatch.setenv("GUARDIAN_LOCAL_DIR", str(tmp_path / "local"))
    from app.local import firmas
    from app.local.escaner import escaner
    firmas.reiniciar()
    escaner._cache = None
    escaner.actual = None
    escaner.ultimo = None
    yield
    if escaner.ocupado():
        escaner.cancelar()
        for _ in range(50):
            if not escaner.ocupado():
                break
            time.sleep(0.1)


@pytest.fixture
def premium(client, auth):
    from app.db.models import User
    from app.db.session import SessionLocal
    db = SessionLocal()
    try:
        for u in db.query(User).all():
            u.plan = "ENTERPRISE"
        db.commit()
    finally:
        db.close()
    return auth


def pe_con_inyeccion() -> bytes:
    """Un PE64 mínimo que importa las tres funciones de inyección de kernel32."""
    funciones = [b"VirtualAllocEx", b"WriteProcessMemory", b"CreateRemoteThread"]
    sec_va, sec_raw = 0x1000, 0x200
    datos = bytearray(0x200)
    desc_off = 0x0
    ilt_off = 0x40
    nombres_off = 0x80
    dll_off = 0x180
    ilt = []
    pos = nombres_off
    for f in funciones:
        ilt.append(sec_va + pos)
        entrada = b"\x00\x00" + f + b"\x00"
        datos[pos:pos + len(entrada)] = entrada
        pos += len(entrada) + (len(entrada) % 2)
    for i, rva in enumerate(ilt + [0]):
        struct.pack_into("<Q", datos, ilt_off + i * 8, rva)
    datos[dll_off:dll_off + 13] = b"kernel32.dll\x00"
    struct.pack_into("<IIIII", datos, desc_off, sec_va + ilt_off, 0, 0, sec_va + dll_off, sec_va + ilt_off)

    cab = bytearray(0x200)
    cab[0:2] = b"MZ"
    struct.pack_into("<I", cab, 0x3C, 0x80)
    cab[0x80:0x84] = b"PE\x00\x00"
    struct.pack_into("<HHIIIHH", cab, 0x84, 0x8664, 1, 0, 0, 0, 240, 0x0022)
    opc = 0x98
    struct.pack_into("<H", cab, opc, 0x20B)
    struct.pack_into("<I", cab, opc + 16, sec_va)          # punto de entrada dentro de la sección
    struct.pack_into("<I", cab, opc + 108, 16)             # NumberOfRvaAndSizes
    struct.pack_into("<II", cab, opc + 112 + 8, sec_va + desc_off, 40)   # directorio de importaciones
    sec = opc + 240
    cab[sec:sec + 8] = b".text\x00\x00\x00"
    struct.pack_into("<IIII", cab, sec + 8, 0x200, sec_va, 0x200, sec_raw)
    struct.pack_into("<I", cab, sec + 36, 0x60000020)      # código, ejecutable, legible
    return bytes(cab + datos)


# ═════════════════════════════════════════════════════════ motor
def test_lector_pe_extrae_importaciones_y_resiste_basura():
    from app.local import pe
    info = pe.analizar(pe_con_inyeccion())
    assert info is not None and info.es_64
    assert {"VirtualAllocEx", "WriteProcessMemory", "CreateRemoteThread"} <= set(info.importaciones)
    assert "kernel32.dll" in info.dlls
    # Cabeceras corruptas a propósito: nunca debe lanzar una excepción ni colgarse.
    base = pe_con_inyeccion()
    for corte in (2, 64, 0x84, 0x100, 0x1F0, 0x250):
        pe.analizar(base[:corte])
    assert pe.analizar(b"MZ" + b"\xff" * 500) is None


def test_motor_detecta_eicar_doble_extension_zip_e_inyeccion(tmp_path):
    from app.local import motor
    from app.local.firmas import base
    (tmp_path / "eicar.com").write_bytes(EICAR)
    (tmp_path / "factura.pdf.exe").write_bytes(pe_con_inyeccion())
    with zipfile.ZipFile(tmp_path / "adjunto.zip", "w") as z:
        z.writestr("foto.jpg.exe", EICAR)
    (tmp_path / "notas.txt").write_text("Lista del mercado: arroz, papa, cebolla")

    v = {p.name: motor.analizar_archivo(p, firmas=base()) for p in tmp_path.iterdir()}
    assert v["eicar.com"].nivel == "malicioso" and v["eicar.com"].accion == "aislar"
    assert v["adjunto.zip"].nivel == "malicioso", "la amenaza dentro del ZIP marca al ZIP"
    assert v["notas.txt"].nivel == "limpio" and v["notas.txt"].accion == "ninguna"
    exe = v["factura.pdf.exe"]
    assert exe.nivel != "limpio"
    assert any("Doble extensión" in m for m in exe.motivos)
    assert any("inyección" in m for m in exe.motivos)
    # Solo heurística: se reporta para que la persona decida, no se aísla solo.
    assert exe.accion == "revisar"


def test_importaciones_sueltas_no_condenan_un_programa_normal():
    """Un juego usa GetAsyncKeyState; eso solo no lo vuelve malware."""
    from app.ai import antivirus as av
    from app.local.motor import _depurar
    dets = [av.Detection(10, av.SUSPICIOUS_IMPORTS["GetAsyncKeyState"][1], "heuristica"),
            av.Detection(6, av.SUSPICIOUS_IMPORTS["WinExec"][1], "heuristica")]
    salida = _depurar(dets, "pe")
    assert sum(d.points for d in salida) <= 10


# ═══════════════════════════════════════════════ análisis por la API
def _esperar(client, auth, estados=("terminado", "cancelado", "error"), limite=30):
    fin = time.time() + limite
    while time.time() < fin:
        s = client.get("/v1/local/escaneo", headers=auth).json()
        if s and s["estado"] in estados:
            return s
        time.sleep(0.1)
    raise AssertionError(f"el análisis no llegó a {estados}: {s}")


def test_escaneo_con_progreso_cuarentena_y_restauracion(client, auth, tmp_path):
    carpeta = tmp_path / "Descargas"
    (carpeta / "sub").mkdir(parents=True)
    (carpeta / "eicar.com").write_bytes(EICAR)
    (carpeta / "sub" / "factura.pdf.exe").write_bytes(pe_con_inyeccion())
    for i in range(40):
        (carpeta / f"doc{i}.txt").write_text(f"documento {i}")

    r = client.post("/v1/local/escaneo", headers=auth, json={"modo": "personalizado", "ruta": str(carpeta)})
    assert r.status_code == 202, r.text
    assert client.post("/v1/local/escaneo", headers=auth,
                       json={"modo": "personalizado", "ruta": str(carpeta)}).status_code in (202, 409)
    final = _esperar(client, auth)
    assert final["estado"] == "terminado" and final["porcentaje"] == 100
    assert final["total"] == 42 and final["analizados"] == 42
    # Los dos son maliciosos, pero solo EICAR (firma conocida) se aísla solo; el otro es heurística.
    assert final["maliciosos"] == 2 and final["aislados"] == 1
    for campo in ("velocidad_archivos_s", "velocidad_mb_s", "hilos_max", "transcurrido_s", "archivo_actual"):
        assert campo in final

    por_nombre = {a["nombre"]: a for a in final["amenazas"]}
    assert por_nombre["eicar.com"]["estado"] == "aislado"
    assert por_nombre["factura.pdf.exe"]["estado"] == "detectado"     # espera la decisión de la persona
    assert not (carpeta / "eicar.com").exists(), "lo malicioso sale de su carpeta"

    # Aislar a mano lo que quedó para decisión (por huella, nunca por ruta arbitraria).
    r = client.post("/v1/local/cuarentena/aislar", headers=auth, json={"sha256": por_nombre["factura.pdf.exe"]["sha256"]})
    assert r.status_code == 200, r.text
    assert client.post("/v1/local/cuarentena/aislar", headers=auth, json={"sha256": "0" * 64}).status_code == 404

    items = client.get("/v1/local/cuarentena", headers=auth).json()
    assert len(items) == 2 and all(i["estado"] == "en_cuarentena" for i in items)
    eicar = next(i for i in items if i["archivo"] == "eicar.com")
    otro = next(i for i in items if i["archivo"] == "factura.pdf.exe")

    r = client.post(f"/v1/local/cuarentena/{eicar['id']}/restaurar", headers=auth)
    assert r.status_code == 200 and (carpeta / "eicar.com").read_bytes() == EICAR   # íntegro tras AES-GCM
    assert client.post(f"/v1/local/cuarentena/{eicar['id']}/restaurar", headers=auth).status_code == 409
    assert client.post(f"/v1/local/cuarentena/{otro['id']}/eliminar", headers=auth).status_code == 200

    # Restaurado = falso positivo declarado: el siguiente análisis ya no lo marca.
    client.post("/v1/local/escaneo", headers=auth, json={"modo": "personalizado", "ruta": str(carpeta)})
    segundo = _esperar(client, auth)
    assert segundo["maliciosos"] == 0
    assert segundo["en_cache"] == 40, "lo que no cambió no se vuelve a leer"

    historial = client.get("/v1/proteccion/escaneos", headers=auth).json()
    assert historial[0]["origen"] == "equipo"


def test_pausar_reanudar_y_detener(client, auth, tmp_path):
    carpeta = tmp_path / "muchos"
    carpeta.mkdir()
    for i in range(3000):
        (carpeta / f"a{i}.bin").write_bytes(b"x" * 512)
    client.post("/v1/local/escaneo", headers=auth,
                json={"modo": "personalizado", "ruta": str(carpeta), "rendimiento": "silencioso"})
    _esperar(client, auth, ("analizando",))
    p = client.post("/v1/local/escaneo/pausar", headers=auth).json()
    assert p["estado"] == "pausado"
    time.sleep(0.4)
    a = client.get("/v1/local/escaneo", headers=auth).json()["analizados"]
    time.sleep(0.5)
    assert client.get("/v1/local/escaneo", headers=auth).json()["analizados"] - a <= 2
    assert client.post("/v1/local/escaneo/reanudar", headers=auth).json()["estado"] == "analizando"
    client.post("/v1/local/escaneo/cancelar", headers=auth)
    final = _esperar(client, auth)
    assert final["estado"] == "cancelado" and final["analizados"] < 3000


def test_ruta_inexistente_y_modo_invalido(client, auth):
    r = client.post("/v1/local/escaneo", headers=auth, json={"modo": "personalizado", "ruta": "Z:\\no\\existe"})
    assert r.status_code == 422
    assert client.post("/v1/local/escaneo", headers=auth, json={"modo": "todo"}).status_code == 422


def test_solo_se_controla_desde_el_propio_equipo(auth):
    """Aunque tenga sesión válida, un dispositivo de la red no puede ordenar analizar ni borrar."""
    from app.main import app
    with TestClient(app, client=("192.168.1.50", 50000)) as otro:
        r = otro.get("/v1/local/estado", headers=auth)
        assert r.status_code == 403 and r.json()["code"] == "solo_local"
        assert otro.post("/v1/local/escaneo", headers=auth, json={"modo": "rapido"}).status_code == 403
        assert otro.post("/v1/local/vpn/conectar", headers=auth).status_code == 403


def test_estado_y_ajustes(client, auth):
    e = client.get("/v1/local/estado", headers=auth).json()
    assert e["firmas"]["total"] >= 1 and "tiempo_real" in e and e["motor"].startswith("local-")
    r = client.put("/v1/local/ajustes", headers=auth, json={"tiempo_real": False, "auto_rapido_horas": 12})
    assert r.status_code == 200 and r.json()["ajustes"]["auto_rapido_horas"] == 12
    assert client.get("/v1/local/estado", headers=auth).json()["es_propietario"] is True


# ═════════════════════════════════════════════════════════════ VPN
CONF = """[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.2.0.2/32
DNS = 10.2.0.1

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 149.88.27.1:51820
"""


def test_importar_conf_valida_y_rechaza_comandos(client, premium):
    r = client.post("/v1/local/vpn/importar", headers=premium, json={"texto": CONF, "nombre": "Proton gratis"})
    assert r.status_code == 200, r.text
    perfil = r.json()["perfil"]
    assert perfil["endpoint"] == "149.88.27.1:51820" and "PrivateKey" not in str(perfil)
    malo = CONF.replace("DNS = 10.2.0.1", "DNS = 10.2.0.1\nPostUp = cmd /c calc.exe")
    r = client.post("/v1/local/vpn/importar", headers=premium, json={"texto": malo})
    assert r.status_code == 409 and "ejecuta comandos" in r.json()["title"]
    parcial = CONF.replace("0.0.0.0/0, ::/0", "10.0.0.0/8")
    assert client.post("/v1/local/vpn/importar", headers=premium, json={"texto": parcial}).status_code == 409


def test_conectar_sin_wireguard_explica_que_instalar(client, premium, monkeypatch):
    from app.local import vpn
    monkeypatch.setattr(vpn, "wireguard", lambda: None)
    client.post("/v1/local/vpn/importar", headers=premium, json={"texto": CONF})
    r = client.post("/v1/local/vpn/conectar", headers=premium)
    assert r.status_code == 409 and "wireguard.com" in r.json()["title"]


def test_conectar_verifica_que_la_ip_cambie(client, premium, monkeypatch):
    """Simula WireGuard: la sesión solo queda «conectada» si la IP pública cambió de verdad."""
    from app.local import sistema, vpn
    ips = iter([{"ip": "191.156.122.42", "pais": "CO", "warp": "off"}] + [{"ip": "104.28.1.9", "pais": "US",
                                                                            "warp": "on"}] * 20)
    monkeypatch.setattr(vpn, "wireguard", lambda: "/usr/bin/wg-quick")
    monkeypatch.setattr(vpn, "trace", lambda timeout=6.0: next(ips))
    monkeypatch.setattr(vpn, "activo", lambda: True)
    monkeypatch.setattr(sistema, "ejecutar_elevado", lambda programa, args, timeout=90.0: 0)
    monkeypatch.setattr(vpn.time, "sleep", lambda s: None)
    client.post("/v1/local/vpn/importar", headers=premium, json={"texto": CONF})
    r = client.post("/v1/local/vpn/conectar", headers=premium)
    assert r.status_code == 200, r.text
    e = r.json()
    assert e["verificado"] is True and e["ip_salida"] == "104.28.1.9" and e["ip_previa"] == "191.156.122.42"
    sesion = client.get("/v1/vpn/sesion", headers=premium).json()
    assert sesion["estado"] == "conectada" and sesion["verificada"] is True
    assert client.post("/v1/local/vpn/desconectar", headers=premium).status_code == 200
    assert client.get("/v1/vpn/sesion", headers=premium).json() is None
