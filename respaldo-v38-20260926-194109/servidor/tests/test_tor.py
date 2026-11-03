"""Modo cebolla del PC: se usa el Tor OFICIAL, solo en 127.0.0.1, y se verifica con el Tor Project."""
import subprocess

from app.local import tor


def test_no_acepta_rutas_de_programa_desde_afuera():
    # La API no recibe rutas: solo se buscan ubicaciones conocidas del tor.exe oficial.
    import inspect
    assert list(inspect.signature(tor.iniciar).parameters) == []


def test_sin_tor_instalado_explica_que_hacer(monkeypatch):
    monkeypatch.setattr(tor, "localizar", lambda: None)
    monkeypatch.setattr(tor, "puerto_activo", lambda: None)
    try:
        tor.iniciar()
        raise AssertionError("debió fallar")
    except tor.ErrorTor as e:
        assert "Tor Browser" in str(e)


def test_arranca_solo_en_localhost_y_sin_dns_fuera(monkeypatch, tmp_path):
    visto = {}

    class Falso:
        stdout = iter(["Bootstrapped 100% (done): Done\n"])
        def poll(self): return None
    def popen(args, **k):
        visto["args"] = args
        f = Falso(); f.stdout = type("S", (), {"readline": iter(["Bootstrapped 100% (done): Done\n", ""]).__next__})()
        return f
    exe = tmp_path / "tor"; exe.write_text("")
    monkeypatch.setattr(tor, "localizar", lambda: exe)
    monkeypatch.setattr(tor, "puerto_activo", lambda: None)
    monkeypatch.setattr(subprocess, "Popen", popen)
    tor.iniciar()
    a = visto["args"]
    assert a[a.index("--SocksPort") + 1] == "127.0.0.1:9050"      # nadie más en la red puede usarlo
    assert "--ClientOnly" in a
    tor._proceso = None


def test_verificacion_usa_socks5h(monkeypatch):
    usado = {}

    class Cliente:
        def __init__(self, proxy=None, timeout=None): usado["proxy"] = proxy
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def get(self, url):
            usado.setdefault("urls", []).append(url)
            return type("R", (), {"json": lambda self: {"IsTor": True, "IP": "185.220.101.1"}})()
    monkeypatch.setattr(tor.httpx, "Client", Cliente)
    r = tor.verificar(9050)
    assert r["is_tor"] is True and r["ip_salida"] == "185.220.101.1"
    assert usado["proxy"] == "socks5h://127.0.0.1:9050"             # DNS dentro de Tor
    assert usado["urls"][0] == "https://check.torproject.org/api/ip"
    assert all(u.startswith("https://") for u in usado["urls"])     # las dos consultas, por el mismo proxy Tor


def test_api_tor(client, auth, monkeypatch):
    from app.local import tor as t
    monkeypatch.setattr(t, "localizar", lambda: None)
    monkeypatch.setattr(t, "puerto_activo", lambda: None)
    e = client.get("/v1/local/tor", headers=auth).json()
    assert e["activo"] is False and e["instalado"] is False
    r = client.post("/v1/local/tor/iniciar", headers=auth)
    assert r.status_code == 409 and "Tor Browser" in r.json()["title"]
    # Con un Tor ya corriendo (p. ej. el de Tor Browser) y verificado, el anonimato es «alto».
    monkeypatch.setattr(t, "puerto_activo", lambda: 9150)
    monkeypatch.setattr(t, "verificar", lambda puerto, timeout=25.0: {"is_tor": True, "ip_salida": "185.220.101.1"})
    e = client.get("/v1/local/tor?verificar=true", headers=auth).json()
    assert e["origen"] == "tor_browser" and e["is_tor"] and e["anonimato"]["nivel"] == "alto"
    assert client.get("/v1/local/tor", headers={}).status_code == 401


def test_compartir_con_el_celular_solo_en_la_red_local(monkeypatch, tmp_path):
    """El proxy para el celular se abre SOLO en las IP privadas de la casa: nunca 0.0.0.0 ni la del túnel."""
    visto = {}

    def popen(args, **k):
        visto["args"] = args
        f = type("P", (), {"poll": lambda self: None})()
        f.stdout = type("S", (), {"readline": iter(["Bootstrapped 100% (done): Done\n", ""]).__next__})()
        return f
    exe = tmp_path / "tor"; exe.write_text("")
    monkeypatch.setattr(tor, "localizar", lambda: exe)
    monkeypatch.setattr(tor, "puerto_activo", lambda: None)
    monkeypatch.setattr(subprocess, "Popen", popen)
    from app import tls
    monkeypatch.setattr(tls, "ips_locales", lambda: ["127.0.0.1", "10.234.28.243", "172.16.0.2", "8.8.4.4"])
    assert tor.compartido() is False                    # apagado por defecto
    tor.iniciar()
    assert "--HTTPTunnelPort" not in visto["args"]
    tor._proceso = None
    tor.ajustes.guardar({"tor_compartir_lan": True})
    try:
        tor.iniciar()
        a = visto["args"]
        puertos = [a[i + 1] for i, x in enumerate(a) if x == "--HTTPTunnelPort"]
        assert puertos == ["10.234.28.243:9080"]         # ni 127.0.0.1, ni WARP (172.16.0.2), ni públicas
        assert not any(p.startswith("0.0.0.0") for p in puertos)
    finally:
        tor.ajustes.guardar({"tor_compartir_lan": False})
        tor._proceso = None


def test_el_celular_solo_lee_el_tor_del_pc(client, auth, monkeypatch):
    from app.local import tor as t
    monkeypatch.setattr(t, "estado", lambda verificar_salida=False: {
        "listo": True, "compartir": {"direcciones": ["10.234.28.243:9080"], "puerto": 9080}})
    r = client.get("/v1/vpn/tor-del-pc", headers=auth).json()
    assert r == {"tor_listo": True, "compartido": True, "direcciones": ["10.234.28.243:9080"], "puerto": 9080,
                 "mensaje": ""}
    assert client.get("/v1/vpn/tor-del-pc").status_code == 401        # sin sesión, nada
    assert client.post("/v1/vpn/tor-del-pc", headers=auth).status_code == 405   # no se puede cambiar desde aquí


def test_pais_de_salida_lista_cerrada_sin_inyeccion(monkeypatch, tmp_path):
    visto = {}

    def popen(args, **k):
        visto["args"] = args
        f = type("P", (), {"poll": lambda self: None})()
        f.stdout = type("S", (), {"readline": iter(["Bootstrapped 100% (done): Done\n", ""]).__next__})()
        return f
    exe = tmp_path / "tor"; exe.write_text("")
    monkeypatch.setattr(tor, "localizar", lambda: exe)
    monkeypatch.setattr(tor, "puerto_activo", lambda: None)
    monkeypatch.setattr(subprocess, "Popen", popen)
    try:
        tor.fijar_pais("us")
        tor._proceso = None
        tor.iniciar()
        a = visto["args"]
        assert a[a.index("--ExitNodes") + 1] == "{us}" and a[a.index("--StrictNodes") + 1] == "1"
        for malo in ["XX", "us} --SocksPort 0.0.0.0:9050 {", "../"]:
            try:
                tor.fijar_pais(malo)
                raise AssertionError("aceptó " + malo)
            except tor.ErrorTor:
                pass
        assert tor.pais_salida() == "US"                  # lo inválido no cambió nada
    finally:
        tor.ajustes.guardar({"tor_pais": None})
        tor._proceso = None


def test_verificacion_confirma_el_pais(monkeypatch):
    class Cliente:
        def __init__(self, proxy=None, timeout=None): pass
        def __enter__(self): return self
        def __exit__(self, *a): return False
        def get(self, url):
            if "torproject" in url:
                return type("R", (), {"json": lambda self: {"IsTor": True, "IP": "185.220.101.1"}})()
            return type("R", (), {"text": "fl=1\nip=185.220.101.1\nloc=DE\nwarp=off\n"})()
    monkeypatch.setattr(tor.httpx, "Client", Cliente)
    tor.ajustes.guardar({"tor_pais": "DE"})
    try:
        r = tor.verificar(9050)
        assert r["pais_real"] == "DE" and r["pais_confirmado"] is True
    finally:
        tor.ajustes.guardar({"tor_pais": None})


def test_api_pais(client, auth):
    assert client.put("/v1/local/tor/pais", headers=auth, json={"pais": "JP"}).json()["pais"]["elegido"] == "JP"
    assert client.put("/v1/local/tor/pais", headers=auth, json={"pais": "us}; rm"}).status_code == 422
    assert client.put("/v1/local/tor/pais", headers=auth, json={"pais": None}).json()["pais"]["elegido"] is None
