"""Ubicaciones VPN: varios .conf (uno por país), importación automática desde Descargas, sin duplicar."""
from pathlib import Path

import pytest

from app.local import vpn

K1 = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="


def conf(peer_key: str, endpoint: str, extra: str = "") -> str:
    return (f"[Interface]\nPrivateKey = {K1}\nAddress = 10.2.0.2/32\nDNS = 10.2.0.1\n{extra}\n"
            f"[Peer]\nPublicKey = {peer_key}\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = {endpoint}\n")


PEERS = ["xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
         "HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw="]


@pytest.fixture(autouse=True)
def _dir_temporal(monkeypatch, tmp_path):
    monkeypatch.setattr(vpn, "_dir", lambda: tmp_path / "vpn")
    (tmp_path / "vpn").mkdir()


@pytest.mark.parametrize("nombre,pais", [("wg-US-FREE-12", "US"), ("JP-FREE#3", "JP"), ("ProtonVPN NL-1", "NL"),
                                         ("uk-free-2", "GB"), ("mi vpn", None), ("conf-casa", None)])
def test_pais_desde_nombre(nombre, pais):
    assert vpn.pais_desde_nombre(nombre) == pais


def test_importar_de_descargas_sin_duplicar_y_rechazando_peligrosos(tmp_path):
    d = tmp_path / "descargas"; d.mkdir()
    (d / "wg-US-FREE-1.conf").write_text(conf(PEERS[0], "185.1.1.1:51820"))
    (d / "wg-JP-FREE-4.conf").write_text(conf(PEERS[1], "185.2.2.2:51820"))
    (d / "wg-NL-FREE-9.conf").write_text(conf(PEERS[2], "185.3.3.3:51820", extra="PostUp = curl evil"))
    (d / "notas.conf").write_text("[general]\nno es wireguard\n")
    r = vpn.importar_de_carpeta(d)
    assert sorted(i["pais"] for i in r["importadas"]) == ["JP", "US"]
    assert len(r["rechazadas"]) == 1 and "PostUp" in r["rechazadas"][0]["motivo"]
    otra = vpn.importar_de_carpeta(d)                     # segunda vez: nada nuevo
    assert otra["importadas"] == [] and otra["repetidas"] == 2
    u = vpn.ubicaciones()
    assert [x["pais"] for x in u] == ["US", "JP"] or sorted(x["pais"] for x in u) == ["JP", "US"]
    assert all("privada" not in str(x).lower() for x in u)   # nunca se exponen claves


def test_usar_ubicacion_cambia_el_tunel_y_valida_el_id(monkeypatch, tmp_path):
    d = tmp_path / "d2"; d.mkdir()
    (d / "wg-US-FREE-1.conf").write_text(conf(PEERS[0], "185.1.1.1:51820"))
    (d / "wg-JP-FREE-4.conf").write_text(conf(PEERS[1], "185.2.2.2:51820"))
    vpn.importar_de_carpeta(d)
    monkeypatch.setattr(vpn, "wireguard", lambda: None)   # sin túnel activo: solo cambia el perfil
    jp = next(u for u in vpn.ubicaciones() if u["pais"] == "JP")
    vpn.usar_ubicacion(jp["id"])
    assert vpn.perfil()["peer"]["Endpoint"] == "185.2.2.2:51820"
    assert next(u for u in vpn.ubicaciones() if u["pais"] == "JP")["en_uso"]
    for malo in ["../../etc", "ABCDEFGH", "1234"]:
        with pytest.raises(vpn.ErrorVpn):
            vpn.usar_ubicacion(malo)


def test_confirma_el_pais_real_al_conectar(tmp_path):
    d = tmp_path / "d3"; d.mkdir()
    (d / "mi-vpn.conf").write_text(conf(PEERS[0], "185.1.1.1:51820"))
    vpn.importar_de_carpeta(d)
    u = vpn.ubicaciones()[0]
    assert u["pais"] is None
    vpn._confirmar_pais_ubicacion("de")
    u = vpn.ubicaciones()[0]
    assert u["pais"] == "DE" and u["verificado"] is True
