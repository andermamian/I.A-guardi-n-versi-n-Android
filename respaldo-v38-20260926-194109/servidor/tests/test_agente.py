"""Agente con privilegios: canal de órdenes, cuarentena y límites de lo que el servidor puede pedir."""
import hashlib
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "agente"))

EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"


@pytest.fixture
def agente_token(client, auth):
    r = client.post("/v1/devices", headers=auth, json={"name": "PC de Anderson", "platform": "windows"})
    token = r.json()["device_token"]
    reg = client.post("/v1/agente/registrar", json={
        "device_token": token, "hostname": "PC-ANDERSON", "so": "Windows 11", "version": "1.0.0",
        "privilegios": True})
    assert reg.status_code == 201, reg.text
    return token, reg.json()["agente_id"]


def _archivo(nombre, **extra):
    base = {"name": nombre, "size": 4096, "sha256": hashlib.sha256(nombre.encode()).hexdigest(),
            "kind": "pe", "header_hex": "4d5a9000", "strings": ["hola"], "entropy": 5.0}
    return {**base, **extra}


def test_registro_exige_token_de_dispositivo_valido(client):
    r = client.post("/v1/agente/registrar", json={"device_token": "x" * 40, "hostname": "ajeno",
                                                   "so": "Windows", "version": "1.0.0"})
    assert r.status_code == 401


def test_agente_sin_registrar_no_puede_reportar(client, auth):
    token = client.post("/v1/devices", headers=auth, json={"name": "Otro", "platform": "windows"}).json()["device_token"]
    r = client.post("/v1/agente/escaneo", json={"device_token": token, "tipo": "rapido",
                                                "archivos_revisados": 1, "candidatos": []})
    assert r.status_code == 409 and r.json()["code"] == "agente_no_registrado"


def test_malicioso_se_manda_a_aislar_y_sospechoso_no(client, agente_token):
    token, _ = agente_token
    peligroso = _archivo("ransom.exe", entropy=7.9, strings=[EICAR, "vssadmin delete shadows"],
                         imports=["CryptEncrypt", "CreateRemoteThread"])
    dudoso = _archivo("instalador.exe", entropy=7.4, strings=["http://descargas.tk/setup"])
    r = client.post("/v1/agente/escaneo", json={
        "device_token": token, "tipo": "completo", "archivos_revisados": 120, "archivos_omitidos": 4,
        "duracion_ms": 9000,
        "candidatos": [{"archivo": peligroso, "ruta": "C:\\Users\\a\\Downloads\\ransom.exe"},
                       {"archivo": dudoso, "ruta": "C:\\Users\\a\\Downloads\\instalador.exe"}]})
    assert r.status_code == 200, r.text
    por_nivel = {v["nivel"]: v for v in r.json()["veredictos"]}
    assert por_nivel["malicioso"]["accion"] == "aislar"
    # Lo sospechoso se informa pero NO se toca: un falso positivo que borra archivos hace más daño.
    assert por_nivel.get("sospechoso", {}).get("accion", "ninguna") == "ninguna"


def test_cuarentena_automatica_desactivada_no_ordena_aislar(client, auth, agente_token):
    token, agente_id = agente_token
    client.put(f"/v1/proteccion/agentes/{agente_id}/politica", headers=auth,
               json={"cuarentena_automatica": False})
    peligroso = _archivo("malo.exe", entropy=7.9, strings=[EICAR], imports=["CryptEncrypt"])
    r = client.post("/v1/agente/escaneo", json={"device_token": token, "tipo": "rapido",
                                                "archivos_revisados": 1,
                                                "candidatos": [{"archivo": peligroso, "ruta": "C:\\x\\malo.exe"}]})
    assert all(v["accion"] == "ninguna" for v in r.json()["veredictos"])


def test_ciclo_completo_de_cuarentena(client, auth, agente_token):
    token, _ = agente_token
    sha = hashlib.sha256(b"malicioso").hexdigest()
    r = client.post("/v1/agente/cuarentena", json={"device_token": token, "items": [
        {"sha256": sha, "filename": "factura.exe", "ruta_original": "C:\\Users\\a\\Downloads\\factura.exe",
         "size_bytes": 20480, "nivel": "malicioso", "familia": "EICAR", "motivos": ["firma conocida"]}]})
    assert r.status_code == 201
    item_id = r.json()["ids"][0]

    lista = client.get("/v1/proteccion/cuarentena", headers=auth).json()
    assert lista[0]["estado"] == "en_cuarentena" and lista[0]["archivo"] == "factura.exe"

    # La persona decide desde la web; el servidor no borra nada por su cuenta.
    d = client.post(f"/v1/proteccion/cuarentena/{item_id}/decidir", headers=auth, json={"accion": "eliminar"})
    assert d.status_code == 202
    tareas = client.post("/v1/agente/latido", json={"device_token": token}).json()["tareas"]
    assert any(t["tipo"] == "eliminar" and t["cuarentena_id"] == item_id for t in tareas)

    client.post("/v1/agente/cuarentena/resultado", json={"device_token": token, "cuarentena_id": item_id,
                                                          "estado": "eliminado"})
    assert client.get("/v1/proteccion/cuarentena", headers=auth).json()[0]["estado"] == "eliminado"
    # No se puede decidir dos veces sobre lo mismo.
    assert client.post(f"/v1/proteccion/cuarentena/{item_id}/decidir", headers=auth,
                       json={"accion": "restaurar"}).status_code == 409


def test_orden_de_escaneo_llega_al_agente(client, auth, agente_token):
    token, agente_id = agente_token
    r = client.post(f"/v1/proteccion/agentes/{agente_id}/escanear", headers=auth,
                    json={"modo": "completo"})
    assert r.status_code == 202
    tareas = client.post("/v1/agente/latido", json={"device_token": token}).json()["tareas"]
    assert tareas[0] == {"tipo": "escanear", "modo": "completo", "ruta": None}
    # La tarea se entrega una sola vez.
    assert client.post("/v1/agente/latido", json={"device_token": token}).json()["tareas"] == []


def test_escaneo_personalizado_exige_ruta(client, auth, agente_token):
    _, agente_id = agente_token
    r = client.post(f"/v1/proteccion/agentes/{agente_id}/escanear", headers=auth,
                    json={"modo": "personalizado"})
    assert r.status_code == 422


def test_agente_ajeno_no_ve_ni_manda(client, auth, agente_token):
    token, agente_id = agente_token
    otro = client.post("/v1/auth/register", json={"email": "intruso@example.com", "full_name": "Intruso Uno",
                                                   "password": "Clave larga distinta 2026", "accept_terms": True})
    h = {"Authorization": f"Bearer {otro.json()['access_token']}"}
    assert client.get("/v1/proteccion/agentes", headers=h).json() == []
    assert client.post(f"/v1/proteccion/agentes/{agente_id}/escanear", headers=h,
                       json={"modo": "rapido"}).status_code == 404


# ───────────────────────── el agente local (código que corre con privilegios)
def test_el_agente_solo_acepta_tareas_de_la_lista_cerrada():
    """Lo más importante del diseño: si el servidor fuera comprometido, no hay ejecución remota."""
    import guardian_agent as ga
    # La lista crece solo con decisiones explícitas. Hoy: analizar, gestionar cuarentena y el túnel.
    assert ga.TAREAS_PERMITIDAS == {"escanear", "restaurar", "eliminar", "vpn_conectar", "vpn_desconectar"}
    # Lo que nunca debe entrar: cualquier cosa que implique ejecutar código llegado del servidor.
    for prohibida in ("ejecutar", "shell", "comando", "descargar", "actualizar_binario", "eval",
                      "instalar", "powershell"):
        assert prohibida not in ga.TAREAS_PERMITIDAS


def test_el_agente_rechaza_una_tarea_desconocida(capsys):
    import guardian_agent as ga
    agente = ga.Agente.__new__(ga.Agente)   # sin red: solo se prueba el filtro
    ga.Agente.ejecutar_tarea(agente, {"tipo": "ejecutar", "comando": "calc.exe"})
    assert "no permitida" in capsys.readouterr().out


def test_rutas_del_sistema_son_intocables():
    import guardian_agent as ga
    for critica in ("C:\\Windows\\System32\\kernel32.dll", "/boot/vmlinuz", "/usr/bin/python3"):
        assert ga.intocable(Path(critica)), critica
    assert not ga.intocable(Path.home() / "Downloads" / "factura.exe")


def test_prioridad_pone_primero_lo_riesgoso(tmp_path):
    import guardian_agent as ga
    descargas = tmp_path / "Downloads"
    descargas.mkdir()
    ejecutable = descargas / "factura.exe"
    ejecutable.write_bytes(b"MZ" + b"\x00" * 100)
    foto = tmp_path / "fotos" / "playa.jpg"
    foto.parent.mkdir()
    foto.write_bytes(b"\xff\xd8\xff" + b"\x00" * 100)
    assert ga.prioridad(ejecutable, 1000) > ga.prioridad(foto, 1000)


def test_cuarentena_cifra_aisla_y_restaura(tmp_path, monkeypatch):
    import guardian_agent as ga
    monkeypatch.setattr(ga, "carpeta_datos", lambda: tmp_path)
    archivo = tmp_path / "trabajo" / "malo.exe"
    archivo.parent.mkdir()
    contenido = b"MZ" + EICAR.encode()
    archivo.write_bytes(contenido)
    sha = hashlib.sha256(contenido).hexdigest()

    q = ga.Cuarentena()
    registro = q.aislar(archivo, sha)
    assert registro and not archivo.exists()          # ya no está donde podía ejecutarse
    aislado = tmp_path / "cuarentena" / f"{sha}.qtn"
    assert aislado.exists() and aislado.read_bytes() != contenido   # guardado cifrado, no en claro
    assert EICAR.encode() not in aislado.read_bytes()

    ok, detalle = q.restaurar(sha)
    assert ok and archivo.read_bytes() == contenido    # vuelve idéntico
    assert not aislado.exists()

    q.aislar(archivo, sha)
    ok, _ = q.eliminar(sha)
    assert ok and not aislado.exists() and sha not in q.listar()


def test_cuarentena_se_niega_a_tocar_el_sistema(tmp_path, monkeypatch, capsys):
    import guardian_agent as ga
    monkeypatch.setattr(ga, "carpeta_datos", lambda: tmp_path)
    monkeypatch.setattr(ga, "intocable", lambda ruta: True)
    archivo = tmp_path / "kernel32.dll"
    archivo.write_bytes(b"MZ")
    assert ga.Cuarentena().aislar(archivo, "a" * 64) is None
    assert archivo.exists()                            # no se movió: el equipo sigue arrancando
    assert "protegida del sistema" in capsys.readouterr().out


def test_rasgos_no_incluyen_el_contenido_del_archivo(tmp_path):
    """Lo que viaja al servidor: huella y rasgos. Nunca el archivo."""
    import guardian_agent as ga
    secreto = tmp_path / "historia_clinica.pdf"
    secreto.write_bytes(b"%PDF-1.7 diagnostico confidencial del paciente" + b"\x00" * 500)
    datos = ga.rasgos(secreto)
    assert datos["kind"] == "pdf" and len(datos["sha256"]) == 64
    assert "contenido" not in datos and "bytes" not in datos


def test_ritmo_se_adapta_a_la_carga_del_equipo(monkeypatch):
    """En un equipo ocioso aprovecha núcleos; si la persona trabaja, se aparta."""
    import guardian_agent as ga
    monkeypatch.setattr(ga.os, "cpu_count", lambda: 16)      # como el Ryzen 7 del usuario
    ritmo = ga.Ritmo("auto")
    assert ritmo.hilos == 8

    monkeypatch.setattr(ga, "_cpu", lambda: 90.0)            # usuario trabajando
    ritmo._ultima_medicion = 0
    ritmo.ajustar()
    assert ritmo.hilos == 2 and ritmo.pausa > 0

    monkeypatch.setattr(ga, "_cpu", lambda: 5.0)             # equipo libre
    ritmo._ultima_medicion = 0
    ritmo.ajustar()
    assert ritmo.hilos == 8 and ritmo.pausa == 0

    assert ga.Ritmo("turbo").hilos == 15                      # el usuario pidió velocidad
    assert ga.Ritmo("silencioso").hilos == 2


def test_instalador_excluye_solo_la_cuarentena():
    """Una exclusión amplia en Defender sería un agujero, no una comodidad."""
    from pathlib import Path
    ps1 = (Path(__file__).resolve().parent.parent / "agente" / "instalar_agente.ps1").read_text("utf-8")
    exclusiones = [l for l in ps1.splitlines() if "Add-MpPreference" in l and not l.strip().startswith("#")]
    assert len(exclusiones) == 1 and "cuarentena" in exclusiones[0]
    assert "-UserId \"SYSTEM\"" in ps1 and "RunLevel Highest" in ps1   # privilegios para el disco completo
    assert "Es-Administrador" in ps1                                   # y verificación previa


def test_el_agente_informa_el_estado_correcto_al_servidor(monkeypatch):
    """Un error de composición de cadena dejaba la cuarentena en «pendiente» para siempre."""
    import guardian_agent as ga
    enviados = []
    agente = ga.Agente.__new__(ga.Agente)
    agente.servidor = type("S", (), {"post": lambda self, ruta, cuerpo: enviados.append((ruta, cuerpo))})()
    agente.cuarentena = type("Q", (), {"restaurar": lambda self, sha: (True, "ok"),
                                       "eliminar": lambda self, sha: (True, "ok")})()
    ga.Agente.ejecutar_tarea(agente, {"tipo": "restaurar", "sha256": "a" * 64, "cuarentena_id": "x"})
    ga.Agente.ejecutar_tarea(agente, {"tipo": "eliminar", "sha256": "b" * 64, "cuarentena_id": "y"})
    estados = [c["estado"] for _, c in enviados]
    assert estados == ["restaurado", "eliminado"]   # valores que el servidor acepta


def test_los_analisis_automaticos_viajan_en_la_politica(client, auth, agente_token):
    """El agente debe saber cada cuánto analizar sin que nadie ejecute nada."""
    token, agente_id = agente_token
    politica = client.post("/v1/agente/latido", json={"device_token": token}).json()["politica"]
    assert politica["auto_rapido_horas"] == 6 and politica["auto_completo_dias"] == 7

    client.put(f"/v1/proteccion/agentes/{agente_id}/politica", headers=auth,
               json={"auto_rapido_horas": 12, "auto_completo_dias": 0})
    politica = client.post("/v1/agente/latido", json={"device_token": token}).json()["politica"]
    assert politica["auto_rapido_horas"] == 12 and politica["auto_completo_dias"] == 0


def test_el_progreso_del_analisis_se_ve_desde_la_web(client, auth, agente_token):
    token, _ = agente_token
    client.post("/v1/agente/latido", json={"device_token": token, "progreso": {
        "modo": "completo", "revisados": 1200, "total": 8000, "actual": "setup.exe", "estado": "analizando"}})
    agente = client.get("/v1/proteccion/agentes", headers=auth).json()[0]
    assert agente["progreso"]["revisados"] == 1200 and agente["progreso"]["total"] == 8000

    # Al terminar, el progreso se limpia y queda la fecha del último análisis.
    client.post("/v1/agente/escaneo", json={"device_token": token, "tipo": "completo",
                                             "archivos_revisados": 8000, "final": True, "candidatos": []})
    agente = client.get("/v1/proteccion/agentes", headers=auth).json()[0]
    assert agente["progreso"] is None and agente["ultimo_completo"] is not None


def test_el_agente_decide_solo_cuando_toca_analizar(monkeypatch, tmp_path):
    """La clave del modo automático: sin intervención y sin molestar si el equipo está ocupado."""
    import guardian_agent as ga
    monkeypatch.setattr(ga, "carpeta_datos", lambda: tmp_path)
    agente = ga.Agente.__new__(ga.Agente)
    agente.politica = {"auto_rapido_horas": 6, "auto_completo_dias": 7}
    ejecutados = []
    agente.escanear = lambda modo, **k: ejecutados.append(modo)

    monkeypatch.setattr(ga, "_cpu", lambda: 90.0)      # la persona está trabajando
    ga.Agente._programados(agente)
    assert ejecutados == [], "no debe estorbar cuando el equipo está ocupado"

    monkeypatch.setattr(ga, "_cpu", lambda: 5.0)       # equipo libre
    ga.Agente._programados(agente)
    assert ejecutados == ["rapido"]
    ga.Agente._programados(agente)
    assert ejecutados == ["rapido"], "no repite antes de que toque de nuevo"
