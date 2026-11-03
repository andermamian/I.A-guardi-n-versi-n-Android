# GuardianIA — App Android v2.0 (Java)

App nativa conectada a tu mismo backend de GuardianIA (Python/FastAPI). La v2 rehace la interfaz y
lleva al teléfono los mismos requisitos del asistente Python: si no hay conexión, la IA sigue
funcionando en el celular con las MISMAS reglas y el MISMO modelo.

## Qué incluye

| Área | Función | Dónde |
|---|---|---|
| Interfaz | 4 pestañas: Inicio, Asistente, Seguridad, Mi teléfono. Tema oscuro, accesible | `ui/MainActivity`, `ui/pantallas/` |
| Asistente | Chat en streaming con el servidor (web, adjuntos, crea Word/PDF, código copiable) | `ai/Asistente`, `AsistenteFragment` |
| IA en el teléfono | Red neuronal de intención (misma que Python, resultados idénticos) | `ai/RedIntencion` + `assets/intencion.json` |
| IA en el teléfono | Guardarraíles: crisis → 192, emergencia médica → 123, oculta correos/teléfonos/tarjetas | `ai/Guardarrailes` |
| IA en el teléfono | Motor sin conexión: revisa enlaces, base de conocimiento, sugiere pantallas | `ai/MotorLocal` |
| Seguridad | Revisar enlaces (sin enviarlos a ningún lado), analizar archivos (no se suben), revisar permisos de apps, generar contraseñas | `SeguridadFragment`, `seguridad/` |
| Antirrobo | Rastreo 24/7, mapa OpenStreetMap, conectar/desconectar (bloqueado si está reportado) | `service/TrackingService`, `DispositivoFragment` |
| Acciones remotas al instante | Canal en vivo con el PC (WebSocket sobre el mismo HTTPS con certificado fijado): las órdenes llegan en milisegundos, no cada 30 s; se reconecta solo | `net/CanalEquipo`, `service/TrackingService` |
| Sonidos propios | Alarma «¡Aquí estoy!» con sirena de GuardianIA (no el despertador); mensajes y bloqueo con aviso propio; pantalla completa sobre el bloqueo aunque la app esté cerrada; botón «Detener» | `service/Sirena`, `res/raw/` |
| Antirrobo | Foto del intruso cifrada en el servidor (solo con el equipo reportado perdido/robado) | `service/IntruderCamera` |
| Emergencia | Botón SOS (mantener 2 s): SMS desde la SIM del teléfono con tu ubicación a todos tus contactos (no necesitan la app; funciona sin datos ni WiFi, solo con señal celular), estado real por contacto, reintentos automáticos sin señal, registro en la cuenta cuando hay internet, «Estoy bien» también por SMS | `InicioFragment`, `ContactosActivity` |
| Cuenta | Inicio de sesión, registro, verificación en dos pasos, renovación automática del token | `net/Sesion`, `CuentaActivity` |
| VPN con IA | WireGuard real (motor oficial): Cloudflare WARP gratis, .conf importado (ProtonVPN…) o servidor propio; verifica que la IP cambió; IA recomienda servidor por propósito; excluye la red de la casa; compatible con «VPN siempre activa» | `vpn/`, `VpnActivity` |
| Inicio de sesión | Solo correo y contraseña: la app encuentra sola el PC en la red (WiFi o punto de acceso) y lo reconoce por su certificado | `net/DescubrirPc`, `CuentaActivity` |
| Conexión con el PC | Acepta la IP como se escriba, obtiene y comprueba el certificado del PC (puerto 8000 o 8443), confirma la huella | `net/ConexionPc` |
| Protección | Tokens cifrados con Android Keystore, huella del certificado del PC en el QR, bloqueo con huella/rostro opcional | `data/CajaFuerte`, `VincularActivity`, `ui/Bloqueo` |

## Cómo compilar (Android Studio)

1. `File → Open` → carpeta `GuardianIA-android`. Deja que descargue dependencias.
2. Conecta el teléfono con **Depuración USB** y pulsa **Run ▶**.
3. APK: `Build → Build APK(s)` → `app/build/outputs/apk/debug/app-debug.apk`.

## Iniciar sesión

Solo correo y contraseña. La app usa el PC guardado; si es la primera vez o el PC cambió de IP, lo
busca en la red en unos segundos. Solo si no lo encuentra aparece «Escribir su dirección».
Si el teléfono ya está vinculado, solo acepta ese mismo PC (mismo certificado), aunque cambie de IP.

## Conectar el teléfono con el PC

1. En el PC: `python -m app.servidor`. La consola muestra la IP y la «Huella de la CA».
2. En el teléfono escribe solo la IP (ej. `10.234.28.243`); la app pone `https://` y el puerto 8443.
3. Si la escribiste a mano, la app muestra la huella: compárala con la de la consola. Con el QR se comprueba sola.

Si dice «No hay conexión con el PC»: mismo WiFi, red marcada como **privada** en Windows y Python
permitido en el firewall. Para abrir los puertos (PowerShell como administrador):

```
netsh advfirewall firewall add rule name="GuardianIA 8443" dir=in action=allow protocol=TCP localport=8443 profile=private
netsh advfirewall firewall add rule name="GuardianIA 8000" dir=in action=allow protocol=TCP localport=8000 profile=private
```

## VPN

Seguridad → «Abrir la VPN». Con **Cloudflare WARP** basta un toque (gratis, sin cuenta). Para elegir
país, importa el `.conf` de ProtonVPN gratis u otro proveedor WireGuard. Mismas reglas que el PC: se
rechazan archivos con PostUp/PreUp (ejecutan comandos), claves inválidas o rutas que no llevan todo el
tráfico, y solo se muestra «verificada» si tu IP pública cambió de verdad. Para que nada salga sin
cifrar si el túnel se cae: «Protección siempre activa» → activa «VPN siempre activa» y «Bloquear
conexiones sin VPN».


## SOS

- Tus contactos reciben un **SMS normal**: no necesitan instalar nada.
- El SMS sale de **tu SIM** con tu plan: no usa datos ni WiFi, solo señal celular (y saldo o plan de SMS).
- Si no hay señal, la app lo reintenta sola cada ~30 s durante 30 min y te muestra el estado real
  («SMS enviado ✓», «sin señal, reintentando»).
- Cuando hay internet (datos o WiFi), el SOS se registra también en tu cuenta. El servidor no repite
  los SMS que ya salieron por la SIM.
- Importante: el servidor todavía usa un proveedor de SMS **simulado** (no envía nada). Para que el
  servidor también envíe SMS/llamadas hay que configurar un proveedor real (Twilio, Infobip).
- La agenda de contactos se guarda en el teléfono al abrir la app o la pantalla Contactos.
- Contactos de emergencia → «SIM para el SOS»: Automática o una SIM concreta, con o sin respaldo en la otra.
- Doble SIM: si una SIM no puede enviar (sin plan de SMS, sin servicio), se prueba con la otra.
- Si el SMS no llega a alguien, el diálogo del SOS ofrece enviarlo por WhatsApp (sirve con WiFi) o con
  la app de mensajes del teléfono, con el texto y la ubicación ya escritos.

## Asistente: respuestas que no se cortan + historial (v3.1)

- La respuesta se genera en el servidor como un «trabajo»: si el teléfono cambia de app, gira la
  pantalla o cambias de chat, la IA SIGUE escribiendo y la respuesta queda guardada.
- En Android, mientras la IA escribe, una notificación «GuardianIA está escribiendo…» evita que
  Android cierre la app; al terminar fuera de la app llega «Tu respuesta está lista».
- Si la red se corta, el teléfono y la web se reconectan solos desde donde quedó la respuesta.
- «Detener» ahora avisa al servidor (cerrar la pantalla ya no detiene la IA).
- Historial: botón de reloj en el chat para abrir o borrar conversaciones; botón «+» para un chat nuevo.
- El chat dibuja mejor: la burbuja de la IA usa todo el ancho, tres puntos animados mientras piensa,
  y la vista solo baja sola si estás al final.

## VPN: solo su servidor de Google Cloud + Tor, con UN botón y la IA (v3.8)

- **Solo dos vías**: su servidor GuardianIA VPN (Google Cloud) y Tor. Se retiraron ProtonVPN, Cloudflare
  WARP, los .conf de terceros, la importación desde Descargas y el «Tor del PC compartido con el
  celular» (el celular trae Tor propio; ese proxy abierto en la red de la casa era superficie de ataque).
  Lo que quedara de esas VPN se borra solo al actualizar (y si un túnel ajeno estaba encendido, se baja).
- **Un botón**: «Proteger ahora». Vía: 🤖 Automático (IA) · Mi servidor · Tor · Servidor + Tor.
  Prioridad: Equilibrio · Velocidad · Anonimato máximo.
- **La IA (cerebro de la VPN)**: mira si su servidor responde, cuánto tarda, cuánto del cupo gratis del
  mes lleva (200 GB en Google Cloud), si Tor está disponible, si la WiFi es pública y lo que aprendió en
  ESE equipo (qué vía conecta y cuánto tarda). Elige, explica por qué (con su confianza y los puntajes)
  y cada 30 s revisa: si algo empeora cambia de vía, con histéresis para no saltar por una medición
  aislada. Es el mismo modelo en el PC (app/ai/cerebro_vpn.py) y en Android (vpn/CerebroVpn.java): las
  pruebas comprueban los mismos casos en ambos.
- **Nunca desprotegido**: si su servidor no responde, pasa a Tor y vuelve solo a su servidor cuando
  responde. Si su servidor ya casi agota el cupo gratis del mes, la IA ahorra y usa Tor.
- **Celular**: Seguridad → VPN. La IA del celular le pregunta al PC cómo está el servidor
  (/vpn/propio/salud, sin claves). Lejos de casa decide con lo que sabe y lo comprueba al conectar.

## Arreglos del servidor Python (v3.7)

- **Agente del PC**: los reportes de escaneo fallaban con error 500 (usaba el motor de apps en vez del
  antivirus). Ahora el servidor decide y ordena aislar lo malicioso como debía.
- **El antivirus ya no se aísla a sí mismo**: su propio código contiene las cadenas que buscan las
  reglas y lo ponía en cuarentena (la app no arrancaba). Solo se exime el CÓDIGO de GuardianIA y el
  Python que la ejecuta; las carpetas de datos se siguen revisando.
- **El asistente aprende**: Asistente → «🧠 Enseñar» («cuando pida X, haz Y»). Se guarda cifrado, se
  aplica solo en preguntas parecidas y nunca puede cambiar las reglas de seguridad.
- **Respuestas mejor escritas**: estructura con subtítulos cuando son largas y ortografía cuidada.
- **Agente**: se niega a enviar su token por http a otro equipo de la red (solo https).
- **Servidor VPN propio**: al reconectar o desconectar, la clave vieja del PC se da de baja allá.

## Tu servidor GuardianIA VPN (v3.6)

Servidor WireGuard propio en Google Cloud (carpeta vpn_servidor del proyecto Python). Se conecta UNA vez:
- **PC**: Protección → VPN → «Mi servidor GuardianIA VPN» → pegar dirección, huella y ficha del instalador.
  GuardianIA comprueba la huella ANTES de enviar la ficha, la guarda cifrada y da de alta el PC.
- **Celular** (misma red que el PC solo esta vez): Seguridad → VPN → «Usar mi servidor GuardianIA».
  Solo viaja la clave PÚBLICA del teléfono; la ficha nunca llega al celular.
- Después: «Activar privacidad». En Velocidad / Uso general gana su servidor; en Anonimato máximo gana
  una ubicación fuera de las alianzas de vigilancia (su servidor puede rastrearse hasta su cuenta).
- El panel muestra el consumo del mes (el servidor frena a los 190 GB para que no le cobren).
- **Sin copiar ni pegar**: `configurar_vpn_propio.py` (en el proyecto Python) conecta el PC leyendo
  GIA_URL, GIA_HUELLA y GIA_FICHA de variables de entorno. `vpn_servidor/rotar-ficha.sh` cambia la
  ficha en la máquina (sin tocar claves, certificado ni dispositivos): úselo si la ficha se expuso.

## Privacidad con UN botón (v3.5): ProtonVPN + Tor

Solo quedan dos vías (se retiraron Cloudflare WARP, Orbot, el Tor compartido del PC y los servidores
propios): varias VPN a la vez generaban conflictos. En el celular, Seguridad → VPN:

- **Modo**: ProtonVPN, Tor, o ProtonVPN + Tor (recomendado: Tor sobre VPN; el operador solo ve Proton,
  Proton solo ve Tor cifrado, los sitios solo ven un relevo Tor). Un único selector: imposible activar
  dos cosas en conflicto.
- **¿Para qué?**: Anonimato / Desbloquear un país / Uso general. GuardianIA ordena tus ubicaciones,
  conecta la mejor, verifica que la IP cambió y, si falla, prueba la siguiente (hasta 3).
- **Ubicaciones automáticas**: vincula tu carpeta Descargas UNA vez; cada .conf de ProtonVPN que
  descargues (account.protonvpn.com → Downloads → WireGuard, uno por país) se importa solo. En el PC:
  «Buscar en mi carpeta Descargas». El país se detecta del nombre y se confirma al conectar.
- **Panel en vivo**: país con bandera, IP, velocidad ↓↑, tiempo conectado, estado de Tor.
- **Tor**: país de salida elegible (lista cerrada). Para Chrome: WiFi → Proxy → Manual → 127.0.0.1:8118
  (no escribirlo en la barra: es un túnel; funciona con sitios https://).
- ProtonVPN no permite generar sus archivos sin iniciar sesión en su cuenta: GuardianIA no guarda su
  contraseña de Proton (sería inseguro). La descarga de los .conf es una sola vez, a mano.

## Tor integrado (v3.4) — sin Orbot y sin PC

- Android: Seguridad → VPN → «Tor integrado en GuardianIA» → Activar Tor. Usa el motor OFICIAL
  (tor-android 0.4.8.16 del Guardian Project, el mismo de Orbot). Queda un proxy solo en el teléfono:
  HTTP 127.0.0.1:8118 (para Chrome: WiFi → Proxy → Manual) y SOCKS 127.0.0.1:9050.
- PC: selector «Tráfico de GuardianIA a internet»: Directo / Por Tor si está activo / Solo por Tor
  (estricto: si Tor no está activo, la búsqueda web, las revisiones de filtraciones y las firmas NO
  salen, así la IP real nunca se expone).
- DNS: socks5h (lo resuelve el relevo de salida de Tor). NO se usa DoH de Cloudflare sobre Tor: fuera
  de Tor filtraría su IP real con cada dominio; dentro, una sola empresa vería todas sus consultas.
- Lo local (modelo de IA en 127.0.0.1, conexión con el celular) nunca pasa por Tor.

## Modo cebolla (Tor) — gratis

- **Android**: con Orbot, la app oficial y gratuita del Tor Project (VPN → «Modo cebolla»):
  1) instalar Orbot, 2) «Activar Tor», 3) «Todo el teléfono por Tor» (modo VPN de Orbot),
  4) «Verificar con el Tor Project». Android permite una sola VPN: la de GuardianIA se desconecta.
- **PC**: Protección → VPN → «Modo cebolla». GuardianIA usa el tor.exe oficial de Tor Browser (o el
  Tor Expert Bundle), escuchando solo en 127.0.0.1:9050, y verifica la salida con el Tor Project.
  Para navegar anónimo, lo mejor es Tor Browser. Requiere `pip install -r requirements.txt` (socksio).
- Si Tor no pasa de «Conectando…», la red puede bloquear Tor: use otra red o puentes (bridges) en
  Tor Browser.

## VPN anónima

La VPN se enfoca en el anonimato, igual en el teléfono y en el PC:

- **Sin registros**: el servidor de GuardianIA ya no guarda su IP real (ni en la sesión ni en la
  auditoría); las IPs que guardaban versiones anteriores se borran y las sesiones cerradas se eliminan
  a las 24 h. El teléfono y el PC solo informan si la IP cambió.
- **Sin fugas**: al importar un .conf, si no trae DNS se usa un DNS privado dentro del túnel (Quad9) y
  se obliga a que el IPv6 también vaya por el túnel (en Windows eso activa el bloqueo de tráfico fuera
  del túnel). WARP se registra sin revelar idioma ni país.
- **Prueba de anonimato**: al conectar se comparan IPv4, IPv6 y resolutor DNS antes y después, y se
  califica de 0 a 100 (IP, IPv6, DNS, kill switch, jurisdicción 5/9/14 Ojos, operador y registros).
  «Alto» solo es posible con un proveedor sin registros auditado (p. ej. ProtonVPN gratis, Suiza):
  WARP protege la privacidad, pero Cloudflare conoce su IP real.
- **Propósito «Anonimato»** en la IA: prefiere países fuera de las alianzas de vigilancia y fuera de
  su propio país.
- **Límites honestos**: la VPN no oculta quién es si inicia sesión en sus cuentas, ni la huella del
  navegador; para anonimato fuerte, Tor Browser.
- En Android, active además «VPN siempre activa» + «Bloquear conexiones sin VPN» (kill switch).

## Salud (retirada)

La función de salud (reloj, Health Connect, pulso por cámara) se retiró de la app y del servidor
porque no podía leer relojes con protocolo cerrado. La app ya no pide permisos de Bluetooth ni de
Health Connect. En el servidor se quitaron la API /vitals, sus motores y la pestaña Salud; las tablas
de la base de datos se conservan sin uso (no se borró ningún dato).

## Más formas de activar el SOS

- **Desde el PC**: Dispositivos → «Enviar SOS desde el teléfono» (pide confirmación). El teléfono envía
  los SMS con SU SIM y su ubicación actual, y le devuelve al PC cuántos salieron. En el teléfono aparece
  un aviso con «Estoy bien» para cancelar si fue una falsa alarma. Queda en la auditoría y se notifica.
- **Panel rápido de Android**: Inicio → «Agregar SOS al panel rápido». Al tocar el botón SOS (sin
  desbloquear) hay 5 s para cancelar; después se envía.
- **Además**, active el SOS de Xiaomi (Ajustes → Seguridad y emergencia → SOS de emergencia: 5 veces el
  botón de encendido): funciona aunque GuardianIA esté cerrada.

## Conexión con el PC (que no se desconecte)

- El teléfono reporta por temporizador aunque el GPS no tenga posición (antes, bajo techo, dejaba de
  reportar y el PC lo mostraba «Desconectado»).
- El canal en vivo se reintenta cada 15 s como máximo y se reabre al instante al volver el WiFi o
  los datos, o cuando el servidor avisa que no lo ve.
- Botón **Conectar** en el PC (Dispositivos → «Conectar ahora») y en la app (Mi teléfono →
  «Conectar con el PC»).
- En Xiaomi/Redmi/POCO: Mi teléfono → «Que no se desconecte» → activar **Inicio automático**,
  batería **Sin restricciones** y bloquear GuardianIA en apps recientes (candado).

## Acciones remotas

Desde el PC (Dispositivos) las órdenes llegan al teléfono en menos de un segundo por el canal en
vivo; si el teléfono no tiene señal, llegan con el siguiente latido o al reconectar. La primera vez,
Android puede pedir permitir «notificaciones de pantalla completa» a GuardianIA: acéptalo para que
la alarma y los mensajes aparezcan sobre la pantalla de bloqueo.

## Pruebas

`./gradlew test` ejecuta:
- `RedIntencionTest`: la red del teléfono normaliza, vectoriza y predice igual que Python.
- `SeguridadLocalTest`: enlaces, guardarraíles y ocultación de datos iguales que el servidor.
- `AnonimatoTest` (incluye Tor): la calificación de anonimato y el cierre de fugas son idénticos a `app/ai/anonimato.py`.
- `VpnTest`: validación de .conf y recomendador IA idénticos a `app/local/vpn.py` y `app/ai/vpn_ai.py`.
- `ConexionPcTest`: direcciones escritas de cualquier forma y errores explicados.
- `DescubrirPcTest`: la búsqueda cubre toda la red local.
- `SmsSosTest`: cada código de error de envío se explica (p. ej. 111 = la SIM no pudo enviarlo) y se sabe si reintentar.
- `CanalEquipoTest`: protocolo WebSocket con el ejemplo oficial del RFC 6455, marcos y fragmentos.

Si reentrenas el modelo en el servidor, la app descarga la versión nueva sola
(`GET /v1/ml/intencion/movil`). Para actualizar también la que viene de fábrica, copia esa respuesta a
`app/src/main/assets/intencion.json` y regenera las referencias de las pruebas.

## Cambios necesarios en el backend (incluidos)

- `POST /v1/devices/self/foto`, `GET/DELETE /v1/devices/{id}/fotos…` — fotos del intruso cifradas (AES-256-GCM).
- Comando `photo` (solo con el equipo reportado).
- `GET /v1/ml/intencion/movil` — exporta la red de intención al teléfono.
- El QR de vinculación incluye la huella SHA-256 de la CA (`&ca=`).
- Pruebas: `tests/test_android_v2.py`.

Límites dichos de frente: Android no deja que una app escanee el contenido de otras (se revisan
permisos); una VPN no hace anónimo a quien inicia sesión en sus cuentas; la app avisa y escala ante
emergencias, pero no reemplaza a los servicios de emergencia (123).
