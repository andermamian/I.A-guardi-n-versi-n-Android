"""Pasarela de privacidad: modos excluyentes, elección de ubicación, conmutación y vigilante."""
import pytest

from app.core import red
from app.local import privacidad as pv, tor, vpn


@pytest.fixture
def entorno(monkeypatch):
    """Simula WireGuard, Tor y tres ubicaciones sin tocar la red real."""
    estado = {"vpn": False, "tor": False, "usada": None, "conectadas": [], "tor_inicios": 0}
    ubic = [{"id": "aaaaaaaa", "nombre": "wg-US-FREE-1", "pais": "US", "pais_nombre": "Estados Unidos",
             "verificado": True, "endpoint": "185.1.1.1:51820", "en_uso": False, "propio": False},
            {"id": "bbbbbbbb", "nombre": "wg-CH-FREE-2", "pais": "CH", "pais_nombre": "Suiza",
             "verificado": False, "endpoint": "185.2.2.2:51820", "en_uso": False, "propio": False},
            {"id": "cccccccc", "nombre": "wg-NL-FREE-3", "pais": "NL", "pais_nombre": "Países Bajos",
             "verificado": True, "endpoint": "185.3.3.3:51820", "en_uso": False, "propio": False}]
    fallan = set()
    monkeypatch.setattr(vpn, "ubicaciones", lambda: ubic)
    monkeypatch.setattr(vpn, "perfil", lambda: {"id": estado["usada"]})
    monkeypatch.setattr(vpn, "wireguard", lambda: "wg")
    monkeypatch.setattr(vpn, "activo", lambda: estado["vpn"])
    monkeypatch.setattr(vpn, "usar_ubicacion", lambda i: estado.update(usada=i))

    def conectar():
        estado["conectadas"].append(estado["usada"])
        ok = estado["usada"] not in fallan
        estado["vpn"] = ok or estado["vpn"]
        return {"verificado": ok}
    monkeypatch.setattr(vpn, "conectar", conectar)
    monkeypatch.setattr(vpn, "desconectar", lambda: estado.update(vpn=False) or {})
    monkeypatch.setattr(vpn, "estadisticas", lambda **k: {})
    monkeypatch.setattr(vpn, "anonimato", lambda **k: {"nivel": "medio", "puntaje": 70})
    monkeypatch.setattr(vpn, "_estado_archivo", lambda: {"pais_previo": "CO"})
    monkeypatch.setattr(tor, "iniciar", lambda: estado.update(tor=True, tor_inicios=estado["tor_inicios"] + 1) or {})
    monkeypatch.setattr(tor, "detener", lambda: estado.update(tor=False) or {})
    monkeypatch.setattr(tor, "estado", lambda **k: {"activo": estado["tor"], "listo": estado["tor"]})
    monkeypatch.setattr(pv, "_circuito", lambda: {})
    monkeypatch.setattr(pv, "latencia_ms", lambda host: {"185.1.1.1": 120, "185.2.2.2": 190, "185.3.3.3": 45,
                                                          "34.9.9.9": 70}[host])
    monkeypatch.setattr(pv, "_asegurar_vigilante", lambda: None)
    yield estado, fallan, ubic
    pv.ajustes.guardar({"privacidad_modo": "apagado", "tor_aislamiento": False})
    red.fijar_modo("apagado")


def test_anonimato_elige_fuera_de_las_alianzas_y_endurece_tor(entorno):
    estado, _, _ = entorno
    r = pv.activar("proton_tor", "anonimato")
    # Suiza está fuera de 5/9/14 Ojos; Países Bajos es de los 9 Ojos y EE. UU. de los 5.
    assert estado["conectadas"] == ["bbbbbbbb"]
    assert estado["vpn"] and estado["tor"] and r["protegido"]
    assert tor.aislamiento() is True                              # un circuito por sitio
    assert red.modo() == "estricto"                               # si Tor cae, nada sale


def test_velocidad_elige_la_menor_latencia(entorno):
    estado, _, _ = entorno
    pv.activar("proton", "velocidad")
    assert estado["conectadas"] == ["cccccccc"]                    # Países Bajos: 45 ms
    assert estado["tor"] is False and red.modo() == "apagado"     # modo sin Tor: Tor apagado


def test_desbloquear_pais_manda_el_pais(entorno):
    estado, _, _ = entorno
    pv.activar("proton", "velocidad", pais="US")
    assert estado["conectadas"] == ["aaaaaaaa"]


def test_servidor_propio_gana_en_velocidad_pero_no_en_anonimato(entorno):
    estado, _, ubic = entorno
    ubic.append({"id": "dddddddd", "nombre": "GuardianIA VPN", "pais": "US", "pais_nombre": "Estados Unidos",
                 "verificado": False, "endpoint": "34.9.9.9:51820", "en_uso": False, "propio": True})
    r = pv.activar("proton", "velocidad")                          # 70 ms, pero es suyo y dedicado
    assert estado["conectadas"] == ["dddddddd"] and "servidor GuardianIA" in r["razon"]
    estado["conectadas"].clear()
    # Para anonimato gana Suiza: el servidor propio puede rastrearse hasta su cuenta (y EE. UU. es 5 Ojos).
    pv.activar("proton_tor", "anonimato")
    assert estado["conectadas"] == ["bbbbbbbb"]
    estado["conectadas"].clear()
    pv.activar("proton", "velocidad", pais="CH")                   # pidió otro país: manda el país
    assert estado["conectadas"] == ["bbbbbbbb"]
    ubic[:] = [u for u in ubic if u["propio"]]                     # si solo tiene su servidor, se usa
    estado["conectadas"].clear()
    pv.activar("proton_tor", "anonimato")
    assert estado["conectadas"] == ["dddddddd"]


def test_conmuta_si_una_ubicacion_no_protege(entorno):
    estado, fallan, _ = entorno
    fallan.add("bbbbbbbb")                                         # la mejor (Suiza) no cambia la IP
    r = pv.activar("proton", "anonimato")
    assert estado["conectadas"] == ["bbbbbbbb", "cccccccc"]         # pasa sola a la siguiente
    assert estado["vpn"] and r["protegido"]


def test_si_ninguna_protege_no_deja_tunel_encendido(entorno):
    estado, fallan, _ = entorno
    fallan.update({"aaaaaaaa", "bbbbbbbb", "cccccccc"})
    with pytest.raises(pv.ErrorPrivacidad):
        pv.activar("proton", "anonimato")
    assert estado["vpn"] is False


def test_modos_excluyentes(entorno):
    estado, _, _ = entorno
    pv.activar("proton_tor", "anonimato")
    pv.activar("tor", "anonimato")
    assert estado["vpn"] is False and estado["tor"] is True        # pasar a Tor apaga la VPN
    pv.activar("apagado")
    assert estado["vpn"] is False and estado["tor"] is False and red.modo() == "apagado"


def test_vigilante_reconecta_y_reinicia(entorno):
    estado, _, _ = entorno
    pv.activar("proton_tor", "anonimato")
    estado["vpn"] = False                                          # el túnel se cae
    assert pv.revisar_una_vez() == "reconectado" and estado["vpn"]
    estado["tor"] = False                                          # Tor se cae
    assert pv.revisar_una_vez() == "tor_reiniciado" and estado["tor"]
    assert pv.revisar_una_vez() is None                            # todo bien


def test_entradas_invalidas(entorno):
    for modo, obj, pais in [("todo", "anonimato", None), ("tor", "rapido", None), ("tor", "anonimato", "us}")]:
        with pytest.raises(pv.ErrorPrivacidad):
            pv.activar(modo, obj, pais)


def test_latencia_no_ejecuta_texto_arbitrario():
    assert pv.latencia_ms("1.1.1.1; rm -rf /") is None
    assert pv.latencia_ms("$(whoami)") is None


def test_rutas(client, auth, entorno):
    r = client.put("/v1/local/privacidad", headers=auth, json={"modo": "proton_tor", "objetivo": "anonimato"})
    assert r.status_code == 200 and r.json()["protegido"] is True
    assert client.get("/v1/local/privacidad", headers=auth).json()["modo"] == "proton_tor"
    assert client.put("/v1/local/privacidad", headers=auth, json={"modo": "x"}).status_code == 422
