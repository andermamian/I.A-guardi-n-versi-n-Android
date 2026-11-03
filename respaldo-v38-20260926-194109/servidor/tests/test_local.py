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


# ═══════════════════════════════════════════════ IA: resumen y asesor
def test_resumen_de_analisis_sin_modelo_es_claro(client, auth, tmp_path):
    from app.local import ia
    assert ia.hay_modelo() is False   # en pruebas no hay LLM
    limpio = ia.resumen_analisis({"analizados": 1200, "limpios": 1200, "sospechosos": 0, "maliciosos": 0,
                                  "aislados": 0, "amenazas": []})
    assert limpio["por_modelo"] is False and "no encontré amenazas" in limpio["texto"]
    con_virus = ia.resumen_analisis({"analizados": 500, "limpios": 497, "sospechosos": 1, "maliciosos": 2,
                                     "aislados": 2, "modo": "rapido",
                                     "amenazas": [{"nombre": "eicar.com", "nivel": "malicioso", "estado": "aislado",
                                                   "motivos": ["Malware conocido"]}]})
    assert "2 amenaza" in con_virus["texto"] and "cuarentena" in con_virus["texto"]


def test_resumen_por_la_api(client, auth, tmp_path):
    carpeta = tmp_path / "d"
    carpeta.mkdir()
    (carpeta / "eicar.com").write_bytes(EICAR)
    client.post("/v1/local/escaneo", headers=auth, json={"modo": "personalizado", "ruta": str(carpeta)})
    _esperar(client, auth)
    r = client.get("/v1/local/analisis/resumen", headers=auth).json()
    assert r["hay"] is True and r["por_modelo"] is False and "amenaza" in r["texto"]


def test_asesor_vpn_ordena_y_explica():
    from app.local import ia
    cat = [{"id": "warp", "nombre": "Cloudflare WARP", "city": "Bogotá", "country": "CO", "load": 0.2,
            "latency_ms": 30, "conectable": True},
           {"id": "importado", "nombre": "Proton CH", "city": "Zúrich", "country": "CH", "load": 0.5,
            "latency_ms": 180, "conectable": True}]
    # Para navegar tranquilo desde Colombia gana el cercano; para privacidad, la jurisdicción suiza.
    general = ia.asesorar_vpn(cat, proposito="general", pais_usuario="CO")
    assert general["recomendado"] == "warp" and general["ranking"][0]["id"] == "warp"
    privacidad = ia.asesorar_vpn(cat, proposito="privacy", pais_usuario="CO")
    assert privacidad["recomendado"] == "importado"
    assert privacidad["texto"] and privacidad["por_modelo"] is False


def test_lista_de_servidores_solo_los_conectables(client, auth):
    r = client.get("/v1/local/vpn/servidores", headers=auth).json()
    ids = {s["id"] for s in r["servidores"]}
    assert "warp" not in ids                 # v3.5: WARP retirado; solo ubicaciones de ProtonVPN
    assert all(s["conectable"] for s in r["servidores"])
    assert not ids or r["asesor"]["recomendado"] in ids
    assert "general" in r["propositos"]


def test_estadisticas_sin_tunel(client, auth):
    st = client.get("/v1/local/vpn/estadisticas", headers=auth).json()
    assert st["activo"] is False and st["segundos_conectado"] == 0


def test_usa_el_modelo_cuando_esta_configurado(client, auth, tmp_path, monkeypatch):
    """Con un LLM configurado, el resumen viene del modelo; si el modelo falla, cae al local."""
    from app.core.config import get_settings
    from app.local import ia
    s = get_settings()
    monkeypatch.setattr(s, "llm_provider", "openai", raising=False)
    monkeypatch.setattr(s, "llm_base_url", "http://modelo.local/v1", raising=False)
    monkeypatch.setattr(s, "llm_model", "demo", raising=False)

    class RespuestaFalsa:
        def raise_for_status(self): pass
        def json(self): return {"choices": [{"message": {"content": "Su equipo está limpio, puede trabajar tranquilo."}}]}

    class ClienteFalso:
        def __init__(self, *a, **k): pass
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def post(self, *a, **k): return RespuestaFalsa()

    monkeypatch.setattr(ia.httpx, "Client", ClienteFalso)
    assert ia.hay_modelo() is True
    r = ia.resumen_analisis({"analizados": 10, "limpios": 10, "sospechosos": 0, "maliciosos": 0, "aislados": 0,
                             "amenazas": []})
    assert r["por_modelo"] is True and "tranquilo" in r["texto"]

    # Si el modelo se cae, el resumen local responde igual.
    def revienta(*a, **k): raise ia.httpx.HTTPError("sin red")
    monkeypatch.setattr(ClienteFalso, "post", revienta)
    r2 = ia.resumen_analisis({"analizados": 10, "limpios": 10, "sospechosos": 0, "maliciosos": 0, "aislados": 0,
                              "amenazas": []})
    assert r2["por_modelo"] is False and r2["texto"]


def test_no_aisla_sus_propios_archivos(tmp_path, monkeypatch):
    """El antivirus jamás debe poner en cuarentena su propio código: eso rompía la app al arrancar."""
    from app.local import motor, sistema
    from app.local.firmas import base
    # Simula que este árbol temporal es la instalación de GuardianIA.
    proyecto = tmp_path / "GuardianIA"
    (proyecto / "app" / "ai").mkdir(parents=True)
    codigo = proyecto / "app" / "ai" / "antivirus.py"
    # Un archivo del propio motor: contiene, a propósito, cadenas que disparan reglas.
    codigo.write_text('EICAR_SHA256 = "..."\nSUSPICIOUS = ["cmd.exe", "CreateRemoteThread", "your files encrypted"]\n')
    monkeypatch.setattr(sistema, "_carpeta_guardian", lambda: sistema._normal(proyecto))

    assert sistema.intocable(codigo) is True
    assert sistema.omitir_recorrido(proyecto / "app") is True
    v = motor.analizar_archivo(codigo, firmas=base())
    # Puede marcarlo o no, pero NUNCA debe proponer aislarlo.
    assert v is None or v.accion != "aislar"


# ═══════════════════════════════════════════════ aprendizaje del asistente
def test_asistente_aprende_de_correccion_y_la_aplica(client, auth):
    """Pulgar abajo con corrección crea una lección; luego se inyecta en preguntas parecidas."""
    from app.ai import aprendizaje
    from app.db.session import SessionLocal
    from app.api.deps import CurrentUser  # noqa: F401
    # Enseñar directamente (equivale a corregir): «para código, incluye comentarios».
    r = client.post("/v1/assistant/aprendizaje", headers=auth,
                    json={"cuando": "cuando pida un programa en python", "haz": "incluye siempre comentarios en español"})
    assert r.status_code == 200
    # La lección aparece en «lo aprendido».
    lo = client.get("/v1/assistant/aprendizaje", headers=auth).json()["lecciones"]
    assert any("comentarios" in l["aprendido"] for l in lo)
    # Y se recupera para una pregunta parecida.
    db = SessionLocal()
    try:
        uid = lo and None
        from app.db.models import User
        user = db.query(User).first()
        bloque = aprendizaje.bloque_para_prompt(db, user.id, "hazme un programa en Python que sume dos números")
        assert "comentarios" in bloque
        # Una pregunta sin relación no arrastra la lección.
        assert aprendizaje.bloque_para_prompt(db, user.id, "¿qué clima hace en Bogotá?") == ""
    finally:
        db.close()
    # Se puede olvidar.
    lid = next(l["id"] for l in lo if "comentarios" in l["aprendido"])
    assert client.delete(f"/v1/assistant/aprendizaje/{lid}", headers=auth).status_code == 200
    assert not client.get("/v1/assistant/aprendizaje", headers=auth).json()["lecciones"]


def test_prompt_estructura_y_ortografia_sin_perder_seguridad():
    from app.ai import assistant
    pr = assistant.system_prompt(assistant.Persona())
    assert "ESTRUCTURA" in pr and "##" in pr and "ORTOGRAFÍA" in pr
    # Menos restricciones, pero las 4 reglas siguen:
    assert "malware" in pr.lower() and "sin su consentimiento" in pr and "123" in pr
    assert "cualquier profesión" in pr


# ═══════════════════════════════════════════════ monitoreo profundo (kernel + red)
def test_kernel_y_red_responden_sin_romperse(client, auth):
    """En Linux ambas capas degradan a vacío; el objetivo es que respondan y no lancen excepción."""
    k = client.get("/v1/local/kernel", headers=auth).json()
    assert "hallazgos" in k and "resumen" in k and isinstance(k["peligrosos"], int)
    n = client.get("/v1/local/red", headers=auth).json()
    assert "dns" in n and "vpn_activa" in n and "resumen" in n
    prof = client.get("/v1/local/revision-profunda", headers=auth).json()
    assert prof["estado"] in ("limpio", "revisar", "peligro")
    assert "kernel" in prof and "red" in prof


def test_kernel_detecta_proceso_del_sistema_en_ubicacion_falsa(monkeypatch):
    """Un «svchost.exe» fuera de System32 es suplantación: debe marcarse como peligroso."""
    from pathlib import Path
    from app.local import kernel
    monkeypatch.setattr(kernel.sistema, "ES_WINDOWS", True)
    monkeypatch.setattr(kernel.sistema, "procesos",
                        lambda: [(1000, "svchost.exe", Path(r"C:\Users\usuario\Downloads\svchost.exe")),
                                 (4, "System", Path(r"C:\Windows\System32\ntoskrnl.exe")),
                                 (800, "svchost.exe", Path(r"C:\Windows\System32\svchost.exe"))])
    hallazgos = kernel.revisar_procesos_sospechosos()
    assert len(hallazgos) == 1
    assert hallazgos[0].nivel == "peligroso" and "ubicación falsa" in hallazgos[0].titulo


def test_kernel_marca_driver_de_alto_riesgo(monkeypatch):
    from app.local import kernel
    monkeypatch.setattr(kernel.sistema, "ES_WINDOWS", True)
    monkeypatch.setattr(kernel, "_driverquery",
                        lambda: [{"module name": "mimidrv", "path": r"C:\temp\mimidrv.sys"},
                                 {"module name": "nvlddmkm", "path": r"C:\Windows\System32\drivers\nvlddmkm.sys"}])
    monkeypatch.setattr(kernel, "_ruta_driver", lambda d: None if "mimidrv" not in d.get("module name", "") else __import__("pathlib").Path("x"))
    hallazgos = kernel.revisar_drivers()
    assert any(h.nivel == "peligroso" and "mimidrv" in h.titulo for h in hallazgos)


def test_red_detecta_conexion_a_puerto_de_malware(monkeypatch):
    from app.local import red
    monkeypatch.setattr(red, "conexiones",
                        lambda: [{"proceso": "raro.exe", "pid": 9, "ip": "45.33.1.2", "puerto": 4444},
                                 {"proceso": "chrome.exe", "pid": 5, "ip": "142.250.1.1", "puerto": 443}])
    hallazgos = red.revisar_conexiones()
    assert len(hallazgos) == 1 and hallazgos[0].nivel == "peligroso" and "4444" in hallazgos[0].titulo


def test_red_detecta_dns_no_reconocido(monkeypatch):
    from app.local import red
    monkeypatch.setattr(red, "servidores_dns", lambda: ["1.1.1.1", "77.88.8.8"])
    hallazgos = red.revisar_dns(vpn_activa=False)
    # 1.1.1.1 es Cloudflare (conocido); 203.0.113.66 no lo es.
    assert len(hallazgos) == 1 and "77.88.8.8" in hallazgos[0].titulo


# ═══════════════════════════════════════════════ vinculación por navegador
def test_qr_apunta_a_pagina_web_no_a_esquema_de_app(client, auth):
    """El QR debe abrir una página web (para que funcione sin instalar app), no guardiania://."""
    r = client.post("/v1/devices/vinculacion", headers=auth, json={"nombre_sugerido": "Mi celular"}).json()
    assert "guardiania://" not in r["url_movil"]
    assert "/vincular?codigo=" in r["url_movil"]
    assert r["codigo"] in r["url_movil"]
    assert r["qr"].startswith("data:image/png;base64,")
    # Y ese código se puede canjear desde el «celular» (sin sesión).
    can = client.post("/v1/devices/vinculacion/canjear",
                      json={"codigo": r["codigo"], "nombre": "Mi celular", "platform": "android"})
    assert can.status_code == 201
    token = can.json()["device_token"]
    # Con el token, el teléfono reporta su ubicación GPS.
    tel = client.post("/v1/devices/telemetry",
                      json={"device_token": token, "lat": 7.88, "lon": -72.51, "accuracy_m": 12, "battery": 80})
    assert tel.status_code == 200


def test_pagina_de_vinculacion_se_sirve(client):
    r = client.get("/vincular")
    assert r.status_code == 200 and "Vincular" in r.text
    js = client.get("/static/app/vincular.js")
    assert js.status_code == 200 and "geolocation" in js.text
    assert client.get("/static/app/vincular.css").status_code == 200


def test_pagina_de_vinculacion_no_la_bloquea_la_csp(client):
    """Regresión: /vincular recibía la CSP de la API (default-src 'none') y el navegador bloqueaba
    todo su CSS/JS y la ubicación: los botones no hacían nada y no salía ningún error."""
    r = client.get("/vincular")
    csp = r.headers["content-security-policy"]
    assert "default-src 'self'" in csp and "script-src 'self'" in csp
    assert "geolocation=(self)" in r.headers["permissions-policy"]
    # Con script-src 'self' (sin 'unsafe-inline') nada en línea puede ejecutarse: no debe haberlo.
    import re
    assert not re.search(r"<script>|<style>|\son[a-z]+=|style=", r.text)
