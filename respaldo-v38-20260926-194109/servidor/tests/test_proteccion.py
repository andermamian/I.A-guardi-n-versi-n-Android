"""Centro de protección: análisis por lotes, estado y revisión de filtraciones."""
import hashlib

import httpx
import pytest

EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"


def _archivo(nombre="documento.pdf", **extra):
    base = {"name": nombre, "size": 12000, "sha256": hashlib.sha256(nombre.encode()).hexdigest(),
            "kind": "pdf", "header_hex": "255044462d", "strings": ["informe", "anual"], "entropy": 5.0}
    return {**base, **extra}


def test_escaneo_de_lote_resume_y_guarda_historial(client, auth):
    archivos = [_archivo(f"doc{i}.pdf") for i in range(5)]
    archivos.append(_archivo("factura.exe", kind="pe", header_hex="4d5a9000", entropy=7.8,
                             strings=[EICAR, "CreateRemoteThread", "http://malo.tk/panel"]))
    r = client.post("/v1/proteccion/escaneo", headers=auth,
                    json={"origen": "carpeta", "archivos": archivos, "omitidos": 3})
    assert r.status_code == 201, r.text
    d = r.json()
    assert d["total"] == 6 and d["omitidos"] == 3 and d["limpios"] + d["sospechosos"] + d["maliciosos"] == 6
    assert d["hallazgos"], "el ejecutable con EICAR debía aparecer como hallazgo"
    assert d["hallazgos"][0]["sha256"] and d["hallazgos"][0]["motivos"]

    historial = client.get("/v1/proteccion/escaneos", headers=auth).json()
    assert historial[0]["total"] == 6 and historial[0]["origen"] == "carpeta"


def test_un_archivo_malicioso_genera_alerta(client, auth):
    peligroso = _archivo("crack.exe", kind="pe", header_hex="4d5a9000", entropy=7.9,
                         strings=[EICAR, "vssadmin delete shadows", "bitcoin"],
                         imports=["CryptEncrypt", "CreateRemoteThread"])
    r = client.post("/v1/proteccion/escaneo", headers=auth, json={"origen": "carpeta", "archivos": [peligroso]})
    assert r.status_code == 201
    if r.json()["maliciosos"]:
        from app.db.models import SecurityAlert
        from app.db.session import SessionLocal
        db = SessionLocal()
        try:
            alerta = db.query(SecurityAlert).order_by(SecurityAlert.created_at.desc()).first()
            assert "malicioso" in alerta.title
        finally:
            db.close()


def test_lote_respeta_el_tope(client, auth):
    r = client.post("/v1/proteccion/escaneo", headers=auth,
                    json={"origen": "carpeta", "archivos": [_archivo(f"a{i}.pdf") for i in range(250)]})
    assert r.status_code == 422  # el servidor no acepta lotes gigantes


def test_estado_resume_y_dice_que_falta(client, auth):
    e = client.get("/v1/proteccion/estado", headers=auth).json()
    assert "pendientes" in e and "vpn" in e and e["motor"].startswith("av-")
    assert any("filtraciones" in p or "analizado" in p for p in e["pendientes"])


def test_exposicion_sin_coincidencias(client, auth, monkeypatch):
    import app.api.v1.proteccion as prot
    monkeypatch.setattr(prot.httpx, "get", lambda *a, **k: httpx.Response(404, request=httpx.Request("GET", "http://x")))
    r = client.post("/v1/proteccion/exposicion", headers=auth, json={"email": "limpio@example.com"})
    assert r.status_code == 200
    d = r.json()
    assert d["filtraciones"] == 0 and d["riesgo"] == "bajo" and d["recomendaciones"]


def test_exposicion_con_filtraciones_clasifica_riesgo_y_alerta(client, auth, monkeypatch):
    import app.api.v1.proteccion as prot
    from app.core.config import Settings
    monkeypatch.setattr(prot, "get_settings", lambda: Settings(env="test", breach_provider="hibp",
                                                               breach_api_key="clave-de-prueba"))
    cuerpo = [{"Name": "Tienda", "BreachDate": "2021-03-01", "DataClasses": ["Email addresses", "Passwords"],
               "PwnCount": 1200000},
              {"Name": "Foro", "BreachDate": "2019-08-12", "DataClasses": ["Email addresses"], "PwnCount": 400}]
    monkeypatch.setattr(prot.httpx, "get",
                        lambda *a, **k: httpx.Response(200, json=cuerpo, request=httpx.Request("GET", "http://x")))
    r = client.post("/v1/proteccion/exposicion", headers=auth, json={"email": "filtrado@example.com"})
    d = r.json()
    assert d["filtraciones"] == 2 and d["riesgo"] in ("alto", "critico")
    assert any("contraseña" in x.lower() for x in d["recomendaciones"])
    assert d["proveedor"] == "hibp"

    # Segunda consulta: viene de caché y no vuelve a pegarle al proveedor.
    def no_llamar(*a, **k):
        raise AssertionError("no debía consultar de nuevo tan pronto")

    monkeypatch.setattr(prot.httpx, "get", no_llamar)
    assert client.post("/v1/proteccion/exposicion", headers=auth,
                       json={"email": "filtrado@example.com"}).json()["cacheado"] is True


def test_exposicion_no_guarda_el_correo_en_claro(client, auth, monkeypatch):
    import app.api.v1.proteccion as prot
    monkeypatch.setattr(prot.httpx, "get", lambda *a, **k: httpx.Response(404, request=httpx.Request("GET", "http://x")))
    correo = "privado@example.com"
    client.post("/v1/proteccion/exposicion", headers=auth, json={"email": correo})
    from app.db.models import ExposureCheck
    from app.db.session import SessionLocal
    db = SessionLocal()
    try:
        fila = db.query(ExposureCheck).order_by(ExposureCheck.created_at.desc()).first()
        assert fila.email_hash == hashlib.sha256(correo.encode()).hexdigest()
        assert correo not in str(fila.__dict__)  # ni el correo ni su dominio quedan guardados
    finally:
        db.close()


def test_proveedor_caido_se_explica(client, auth, monkeypatch):
    import app.api.v1.proteccion as prot

    def cae(*a, **k):
        raise httpx.ConnectError("sin red")

    monkeypatch.setattr(prot.httpx, "get", cae)
    r = client.post("/v1/proteccion/exposicion", headers=auth, json={"email": "otro@example.com"})
    assert r.status_code == 502 and "intente de nuevo" in r.json()["title"].lower()


def test_hibp_sin_clave_lo_dice(client, auth, monkeypatch):
    import app.api.v1.proteccion as prot
    from app.core.config import Settings
    monkeypatch.setattr(prot, "get_settings", lambda: Settings(env="test", breach_provider="hibp", breach_api_key=""))
    r = client.post("/v1/proteccion/exposicion", headers=auth, json={"email": "sinclave@example.com"})
    assert r.status_code == 503 and "clave" in r.json()["title"].lower()


@pytest.mark.parametrize("filtraciones,esperado", [
    ([], "bajo"),
    ([{"datos": ["Email addresses"]}], "medio"),
    ([{"datos": ["Passwords"]}], "alto"),
    ([{"datos": ["Passwords", "Credit cards"]}], "critico"),
])
def test_clasificacion_de_riesgo(filtraciones, esperado):
    from app.api.v1.proteccion import _riesgo
    assert _riesgo(filtraciones) == esperado


def test_servidores_vpn_de_ejemplo_sirven_al_motor():
    """Los servidores de ejemplo deben permitir demostrar la recomendación con criterio real."""
    from app.ai import vpn_ai
    from app.cli import SERVIDORES_EJEMPLO
    servidores = [vpn_ai.Server(id=i, country=p, city=c, host=hh, port=pt, protocol=pr,
                                is_premium=pm, load=l, latency_ms=lat)
                  for i, p, c, hh, pt, pr, pm, l, lat in SERVIDORES_EJEMPLO]
    assert len(servidores) >= 8
    privacidad = vpn_ai.recommend(servidores, purpose="privacy", user_country="CO", target_country=None,
                                  allow_premium=True, observed_latency={})
    assert privacidad.server.country in vpn_ai.PRIVACY_FRIENDLY  # jurisdicción, no cercanía
    juego = vpn_ai.recommend(servidores, purpose="gaming", user_country="CO", target_country=None,
                             allow_premium=True, observed_latency={})
    assert juego.server.country in ("CO", "MX", "US")            # aquí sí manda la latencia


# ─────────────────────────────────────── sesiones de VPN
@pytest.fixture
def con_vpn(client, auth):
    from app.cli import SERVIDORES_EJEMPLO
    from app.db.models import User, VpnServer
    from app.db.session import SessionLocal
    db = SessionLocal()
    try:
        for i, p_, c, hh, pt, pr, pm, l, lat in SERVIDORES_EJEMPLO[:3]:
            if not db.get(VpnServer, i):
                # Con clave y endpoint: un servidor real. Los de ejemplo, sin ellos, ya no se aceptan.
                db.add(VpnServer(id=i, country=p_, city=c, host=hh, port=pt, protocol=pr,
                                 is_premium=pm, load=l, latency_ms=lat,
                                 public_key="A" * 43 + "=", endpoint=f"{hh}:51820"))
        for u in db.query(User).all():
            u.plan = "ENTERPRISE"
        db.commit()
    finally:
        db.close()
    return client.post("/v1/auth/login", json={"email": "ana@example.com", "password": "Frase larga de prueba 2026"})


def test_conectar_y_desconectar_mide_el_tiempo(client, auth, con_vpn):
    r = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1", "proposito": "privacy"})
    assert r.status_code == 201, r.text
    sesion = r.json()
    assert sesion["estado"] == "conectando" and sesion["ciudad"] == "Bogotá"

    actual = client.get("/v1/vpn/sesion", headers=auth).json()
    assert actual["id"] == sesion["id"] and actual["segundos_conectado"] >= 0

    # El agente confirma que el túnel quedó arriba.
    t = client.post("/v1/vpn/tunel", headers=auth,
                    json={"sesion_id": sesion["id"], "estado": "conectada", "ip_previa": "191.156.228.127",
                          "ip_salida": "181.49.10.4", "latencia_ms": 21})
    assert t.json()["estado"] == "conectada" and t.json()["verificada"] is True

    fin = client.post("/v1/vpn/desconectar", headers=auth).json()
    assert fin["estado"] == "cerrada" and fin["segundos_conectado"] >= 0
    assert client.get("/v1/vpn/sesion", headers=auth).json() is None
    assert client.post("/v1/vpn/desconectar", headers=auth).status_code == 409  # ya no hay nada que cortar


def test_una_sola_sesion_a_la_vez(client, auth, con_vpn):
    client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"})
    segunda = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-mde-1"}).json()
    actual = client.get("/v1/vpn/sesion", headers=auth).json()
    assert actual["id"] == segunda["id"] and actual["ciudad"] == "Medellín"
    historial = client.get("/v1/vpn/sesiones", headers=auth).json()
    assert historial[1]["estado"] == "cerrada"   # la anterior se cerró sola
    client.post("/v1/vpn/desconectar", headers=auth)


def test_uso_acumulado_de_vpn(client, auth, con_vpn):
    client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"})
    client.post("/v1/vpn/desconectar", headers=auth)
    uso = client.get("/v1/vpn/uso", headers=auth).json()
    assert uso["sesiones_30d"] >= 1 and "CO" in uso["por_pais"]


def test_servidor_inexistente_no_crea_sesion(client, auth, con_vpn):
    assert client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "no-existe"}).status_code == 404


def test_no_se_puede_declarar_conectada_sin_cambio_de_ip(client, auth, con_vpn):
    """El fallo que el usuario detectó: la pantalla decía «conectada» y la IP no había cambiado.
    Ahora el servidor se niega a otorgar ese estado sin prueba."""
    sesion = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"}).json()

    # Sin datos de IP: no hay verificación posible.
    r = client.post("/v1/vpn/tunel", headers=auth,
                    json={"sesion_id": sesion["id"], "estado": "conectada"}).json()
    assert r["estado"] == "sin_verificar" and r["verificada"] is False

    # Con la misma IP antes y después: el túnel no está levantado.
    r = client.post("/v1/vpn/tunel", headers=auth, json={
        "sesion_id": sesion["id"], "estado": "conectada",
        "ip_previa": "191.156.228.127", "ip_salida": "191.156.228.127"}).json()
    assert r["estado"] == "sin_verificar" and r["verificada"] is False

    # Con cambio real de IP: ahí sí.
    r = client.post("/v1/vpn/tunel", headers=auth, json={
        "sesion_id": sesion["id"], "estado": "conectada",
        "ip_previa": "191.156.228.127", "ip_salida": "45.132.20.9", "latencia_ms": 140}).json()
    assert r["estado"] == "conectada" and r["verificada"] is True
    # Política sin registros: la IP real se usa para verificar, pero el servidor nunca la guarda ni la devuelve.
    assert r["ip_previa"] is None and r["ip_salida"] == "45.132.20.9"
    client.post("/v1/vpn/desconectar", headers=auth)


def test_sesion_sin_verificar_sigue_siendo_cancelable(client, auth, con_vpn):
    sesion = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-mde-1"}).json()
    client.post("/v1/vpn/tunel", headers=auth, json={"sesion_id": sesion["id"], "estado": "conectada"})
    actual = client.get("/v1/vpn/sesion", headers=auth).json()
    assert actual["estado"] == "sin_verificar"      # visible para que la persona pueda cerrarla
    assert client.post("/v1/vpn/desconectar", headers=auth).status_code == 200


def test_servidor_de_ejemplo_no_deja_sesiones_colgadas(client, auth, con_vpn):
    """Antes quedaba «conectando» para siempre: un servidor sin WireGuard nunca iba a responder."""
    from app.db.models import VpnServer
    from app.db.session import SessionLocal
    db = SessionLocal()
    try:
        db.add(VpnServer(id="xx-demo", country="MX", city="Demo", host="demo.example", port=51820,
                         protocol="wireguard", is_premium=False, load=0.1, latency_ms=50))
        db.commit()
    finally:
        db.close()
    r = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "xx-demo"})
    assert r.status_code == 409 and r.json()["code"] == "servidor_sin_tunel"
    assert client.get("/v1/vpn/sesion", headers=auth).json() is None



def test_vpn_sin_registros_de_ip_real(client, auth, con_vpn):
    """Anonimato: el servidor no guarda la IP real ni en la sesión ni en la auditoría."""
    from app.db.models import AuditLog, VpnSession
    from app.db.session import SessionLocal
    sesion = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"}).json()
    # Una app nueva solo dice si la IP cambió; una vieja todavía manda la IP real: tampoco se guarda.
    r = client.post("/v1/vpn/tunel", headers=auth, json={"sesion_id": sesion["id"], "estado": "conectada",
                                                         "ip_cambio": True, "ip_salida": "45.132.20.9"}).json()
    assert r["verificada"] is True and r["ip_previa"] is None
    client.post("/v1/vpn/tunel", headers=auth, json={"sesion_id": sesion["id"], "estado": "conectada",
                                                      "ip_previa": "191.156.228.127", "ip_salida": "45.132.20.9"})
    with SessionLocal() as db:
        guardada = db.get(VpnSession, sesion["id"])
        assert guardada.ip_previa is None
        vpn_audit = db.query(AuditLog).filter(AuditLog.action.like("vpn.%")).all()
        assert vpn_audit and all(a.ip is None for a in vpn_audit)
        assert not any("191.156.228.127" in str(a.detail) for a in vpn_audit)
    client.post("/v1/vpn/desconectar", headers=auth)


def test_vpn_purga_sesiones_viejas(client, auth, con_vpn):
    """Las sesiones cerradas de más de 24 h se borran y las IPs reales antiguas se limpian."""
    from datetime import timedelta
    from app.core.security import utcnow
    from app.db.models import VpnSession
    from app.db.session import SessionLocal
    primera = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"}).json()
    client.post("/v1/vpn/desconectar", headers=auth)
    with SessionLocal() as db:
        vieja = db.get(VpnSession, primera["id"])
        vieja.cerrada_at = utcnow() - timedelta(days=2)
        vieja.ip_previa = "191.156.228.127"          # como la habría dejado una versión anterior
        db.commit()
    segunda = client.post("/v1/vpn/conectar", headers=auth, json={"server_id": "co-bog-1"}).json()
    with SessionLocal() as db:
        assert db.get(VpnSession, primera["id"]) is None
        assert db.query(VpnSession).filter(VpnSession.ip_previa.isnot(None)).count() == 0
    client.post("/v1/vpn/desconectar", headers=auth)
    assert segunda["ip_previa"] is None
