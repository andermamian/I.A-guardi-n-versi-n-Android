"""v3.8: la única ubicación VPN es su servidor GuardianIA VPN. Lo de terceros (ProtonVPN, WARP, .conf
importados) se purga y nunca se usa, aunque quede en disco de una versión anterior."""
import json

import pytest

from app.local import vpn

K = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="


@pytest.fixture(autouse=True)
def _dir_temporal(monkeypatch, tmp_path):
    monkeypatch.setattr(vpn, "_dir", lambda: tmp_path / "vpn")
    (tmp_path / "vpn").mkdir()


def _ubic(id_, propio, endpoint):
    p = {"id": id_, "nombre": "GuardianIA VPN (us-central1)" if propio else "wg-CH-FREE-2", "pais": "US",
         "proveedor": "servidor" if propio else "importado", "propio": propio,
         "interfaz": {"Address": "10.66.0.2/32", "DNS": "10.66.0.1"},
         "peer": {"PublicKey": K, "Endpoint": endpoint, "AllowedIPs": "0.0.0.0/0, ::/0"}}
    vpn._guardar_ubicacion(p, "privada-" + id_)
    return p


def test_solo_se_listan_las_propias():
    _ubic("aaaaaaaa", False, "185.2.2.2:51820")
    _ubic("bbbbbbbb", True, "34.1.2.3:51820")
    assert [u["id"] for u in vpn.ubicaciones()] == ["bbbbbbbb"]


def test_purga_proton_y_perfil_ajeno_sin_tocar_el_propio(monkeypatch):
    _ubic("aaaaaaaa", False, "185.2.2.2:51820")
    _ubic("bbbbbbbb", True, "34.1.2.3:51820")
    vpn._guardar_perfil({"proveedor": "importado", "nombre": "ProtonVPN CH", "peer": {}, "interfaz": {}})
    vpn._escribir_privado(vpn._dir() / "importada.key", "clave-de-proton")
    bajado = []
    monkeypatch.setattr(vpn, "wireguard", lambda: "wg")
    monkeypatch.setattr(vpn, "activo", lambda: True)
    monkeypatch.setattr(vpn, "desconectar", lambda: bajado.append(1) or {})
    r = vpn.purgar_terceros()
    assert r == {"ubicaciones_borradas": 1, "perfil_quitado": True}
    assert bajado == [1]                                   # el túnel ajeno se bajó antes de borrarlo
    assert vpn.perfil() is None and not (vpn._dir() / "importada.key").exists()
    assert (vpn._dir() / "ubicaciones" / "bbbbbbbb" / "perfil.json").exists()
    # Con el perfil propio activo, la purga no lo toca.
    monkeypatch.setattr(vpn, "activo", lambda: False)
    vpn.usar_ubicacion("bbbbbbbb")
    assert vpn.purgar_terceros() == {"ubicaciones_borradas": 0, "perfil_quitado": False}
    assert vpn.perfil()["proveedor"] == "servidor"


def test_conectar_se_niega_con_un_perfil_ajeno(monkeypatch):
    vpn._guardar_perfil({"proveedor": "importado", "nombre": "ProtonVPN", "peer": {}, "interfaz": {}})
    monkeypatch.setattr(vpn, "wireguard", lambda: "wg")
    with pytest.raises(vpn.ErrorVpn, match="servidor GuardianIA VPN"):
        vpn.conectar()


def test_id_malicioso_no_toca_archivos():
    for malo in ["../../etc", "..", "ZZZZZZZZ", ""]:
        with pytest.raises(vpn.ErrorVpn):
            vpn.usar_ubicacion(malo)


def test_ninguna_clave_privada_en_la_lista():
    _ubic("bbbbbbbb", True, "34.1.2.3:51820")
    assert "privada-" not in json.dumps(vpn.ubicaciones())
