"""Asistente de IA con navegación web — interfaces AI Communication / Personality Designer.

La IA responde preguntas y, cuando hace falta, busca en internet y lee páginas para dar una
respuesta con fuentes (defensa SSRF en web_browser.py). El historial se guarda cifrado.
"""
from __future__ import annotations

import io
import json
import logging

import httpx
from fastapi import APIRouter, File, Form, Query, Request, UploadFile
from fastapi.responses import StreamingResponse
from sqlalchemy import func, select

from app.ai import aprendizaje, assistant, estudio, lectura
from app.api.deps import DB, CurrentUser, client_ip
from app.core import audit
from app.core.config import get_settings
from app.core.crypto import open_text, seal_text
from app.api.v1.vault import safe_filename, sniff_mime
from app.core.errors import ApiError, conflict, not_found
from app.core.ratelimit import limiter
from pydantic import BaseModel, Field

from app.db.models import Attachment, Conversation, Message, PersonalityProfile
from app.db.session import SessionLocal
from app.services import boveda, chat_trabajos
from app.schemas.common import Msg
from app.schemas.security import (ChatIn, ChatMessageOut, ChatOut, ConversationOut, PersonalityIO, SourceOut)

log = logging.getLogger("guardian")
router = APIRouter(tags=["Asistente de IA"])


def _persona(db, user_id: str) -> tuple[PersonalityProfile, assistant.Persona]:
    pp = db.get(PersonalityProfile, user_id)
    if pp is None:
        pp = PersonalityProfile(user_id=user_id)
        db.add(pp)
        db.flush()
    persona = assistant.Persona(name=pp.assistant_name, friendliness=pp.friendliness, protection=pp.protection,
                                proactivity=pp.proactivity, verbosity=pp.verbosity, humor=pp.humor, mode=pp.mode)
    return pp, persona


@router.post("/assistant/chat", response_model=ChatOut)
def chat(body: ChatIn, p: CurrentUser, db: DB):
    limiter.hit(f"chat:{p.user.id}", 30)
    _, persona = _persona(db, p.user.id)
    if body.conversation_id:
        conv = db.get(Conversation, body.conversation_id)
        if conv is None or conv.user_id != p.user.id:
            raise not_found("conversación")
    else:
        conv = Conversation(user_id=p.user.id, title=body.message[:60])
        db.add(conv)
        db.flush()
    history = [assistant.Turn(role=m.role, content=open_text(m.content_enc, f"msg:{m.id}") or "")
               for m in db.execute(select(Message).where(Message.conversation_id == conv.id)
                                   .order_by(Message.created_at).limit(24)).scalars()]

    s = get_settings()
    prev = s.web_browsing_enabled
    client = httpx.Client(timeout=max(s.llm_timeout_seconds, s.web_timeout_seconds))
    try:
        if not body.allow_web:
            # Respeta el interruptor por-mensaje sin tocar la configuración global.
            object.__setattr__(s, "web_browsing_enabled", False)
        docs = _documentos(db, conv.id)
        doc_context, usados = lectura.contexto_para(body.message, docs) if docs else ("", [])
        lecciones = aprendizaje.bloque_para_prompt(db, p.user.id, body.message)
        reply = assistant.respond(body.message, history, persona, doc_context=doc_context, client=client,
                                  lecciones=lecciones)
    finally:
        object.__setattr__(s, "web_browsing_enabled", prev)
        client.close()

    user_msg = Message(conversation_id=conv.id, role="user",
                       content_enc=seal_text(body.message, "msg:pending"))
    db.add(user_msg)
    db.flush()
    user_msg.content_enc = seal_text(body.message, f"msg:{user_msg.id}")
    bot_msg = Message(conversation_id=conv.id, role="assistant", content_enc=seal_text(reply.text, "msg:pending"),
                      safety=reply.safety, provider=reply.provider, latency_ms=reply.latency_ms)
    db.add(bot_msg)
    db.flush()
    bot_msg.content_enc = seal_text(reply.text, f"msg:{bot_msg.id}")
    db.commit()
    return ChatOut(conversation_id=conv.id, respuesta=reply.text, proveedor=reply.provider,
                   busco_en_web=reply.searched_web, fuentes=[SourceOut(**s) for s in reply.sources],
                   acciones_sugeridas=reply.suggested_actions, seguridad=reply.safety)


MAX_ADJUNTOS = 6


def _conversacion(db, user_id: str, conversation_id: str | None, titulo: str):
    if conversation_id:
        conv = db.get(Conversation, conversation_id)
        if conv is None or conv.user_id != user_id:
            raise not_found("conversación")
        return conv
    conv = Conversation(user_id=user_id, title=titulo[:60])
    db.add(conv)
    db.flush()
    return conv


def _documentos(db, conversation_id: str) -> list[lectura.Documento]:
    """Carga los adjuntos de la conversación ya descifrados, listos para buscar en ellos."""
    docs = []
    for a in db.execute(select(Attachment).where(Attachment.conversation_id == conversation_id)
                        .order_by(Attachment.created_at)).scalars():
        texto = open_text(a.text_enc, f"adj:{a.id}") or ""
        if not texto:
            continue
        doc = lectura.Documento(nombre=a.filename, mime=a.mime, texto=texto, paginas=a.pages,
                                truncado=a.truncated, metodo=a.method)
        doc.fragmentos = lectura.fragmentar(texto)
        docs.append(doc)
    return docs


@router.post("/assistant/attachments", status_code=201)
async def subir_adjunto(p: CurrentUser, db: DB, request: Request,
                        conversation_id: str | None = Form(None), file: UploadFile = File(...)):
    """Lee un archivo para que el asistente pueda responder sobre su contenido.

    Se guarda el texto extraído, cifrado, no el archivo. Las imágenes guardan además una copia
    reducida en base64 por si el modelo es de visión.
    """
    limiter.hit(f"adjunto:{p.user.id}", 20, 600)
    s = get_settings()
    maximo = s.max_upload_mb * 1024 * 1024
    datos = await file.read(maximo + 1)
    if len(datos) > maximo:
        raise ApiError(413, "file_too_large", f"El archivo supera {s.max_upload_mb} MB")
    if not datos:
        raise ApiError(422, "empty_file", "El archivo está vacío")

    nombre = safe_filename(file.filename or "archivo")
    mime = sniff_mime(datos[:64], nombre) or file.content_type or ""
    try:
        doc = lectura.extraer(nombre, mime, datos)
    except lectura.NoSePuedeLeer as e:
        raise ApiError(422, "no_legible", str(e)) from e

    conv = _conversacion(db, p.user.id, conversation_id, f"Sobre {nombre}")
    if db.execute(select(func.count()).select_from(Attachment)
                  .where(Attachment.conversation_id == conv.id)).scalar_one() >= MAX_ADJUNTOS:
        raise conflict("demasiados_adjuntos", f"Máximo {MAX_ADJUNTOS} archivos por conversación")

    a = Attachment(conversation_id=conv.id, user_id=p.user.id, filename=nombre, mime=mime,
                   size_bytes=len(datos), pages=doc.paginas, method=doc.metodo, truncated=doc.truncado,
                   chars=len(doc.texto), text_enc="")
    db.add(a)
    db.flush()
    a.text_enc = seal_text(doc.texto, f"adj:{a.id}")
    if mime.startswith("image/"):
        miniatura = _miniatura(datos, mime)
        if miniatura:
            a.image_enc = seal_text(miniatura, f"img:{a.id}")
    audit.record(db, "assistant.adjunto", actor_id=p.user.id, target_type="attachment", target_id=a.id,
                 ip=client_ip(request), detail={"mime": mime, "bytes": len(datos), "metodo": doc.metodo},
                 commit=False)
    db.commit()
    return {"id": a.id, "conversation_id": conv.id, "nombre": nombre, "mime": mime,
            "bytes": len(datos), "paginas": doc.paginas, "caracteres": len(doc.texto),
            "metodo": doc.metodo, "truncado": doc.truncado, "fragmentos": len(doc.fragmentos),
            "vista_previa": doc.texto[:400]}


def _miniatura(datos: bytes, mime: str) -> str:
    """Reduce la imagen antes de mandarla a un modelo de visión: menos memoria y más rapidez."""
    try:
        import base64

        from PIL import Image
        img = Image.open(io.BytesIO(datos))
        img.thumbnail((1024, 1024))
        if img.mode not in ("RGB", "L"):
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=80)
        return base64.b64encode(buf.getvalue()).decode()
    except Exception:  # noqa: BLE001 - si falla, simplemente no hay visión para esa imagen
        return ""


@router.get("/assistant/conversations/{conversation_id}/attachments")
def listar_adjuntos(conversation_id: str, p: CurrentUser, db: DB):
    conv = db.get(Conversation, conversation_id)
    if conv is None or conv.user_id != p.user.id:
        raise not_found("conversación")
    return [{"id": a.id, "nombre": a.filename, "mime": a.mime, "bytes": a.size_bytes,
             "paginas": a.pages, "caracteres": a.chars, "metodo": a.method, "creado": a.created_at}
            for a in db.execute(select(Attachment).where(Attachment.conversation_id == conv.id)
                                .order_by(Attachment.created_at)).scalars()]


@router.delete("/assistant/attachments/{attachment_id}", response_model=Msg)
def borrar_adjunto(attachment_id: str, p: CurrentUser, db: DB):
    a = db.get(Attachment, attachment_id)
    if a is None or a.user_id != p.user.id:
        raise not_found("adjunto")
    db.delete(a)
    db.commit()
    return Msg(mensaje="Archivo retirado de la conversación")


@router.post("/assistant/chat/stream")
def chat_stream(body: ChatIn, request: Request, p: CurrentUser, db: DB):
    """Igual que /assistant/chat, pero enviando el texto a medida que el modelo lo produce.

    Un modelo local en CPU puede tardar minutos; sin esto la pantalla se queda vacía y el navegador
    corta. Aquí el primer trozo llega en segundos.

    Detalles que importan:
    - La conversación y el historial se leen ANTES de empezar a transmitir, y la sesión de base de
      datos se cierra: mantenerla abierta durante minutos agotaría el pool con pocos usuarios.
    - El mensaje del usuario se guarda de una vez; la respuesta, al terminar, con su propia sesión.
    - La respuesta se genera en un trabajo propio (services/chat_trabajos.py): si la persona cambia de
      app, gira la pantalla o cambia de chat, la respuesta SIGUE y queda guardada. El primer evento
      trae ``trabajo_id`` para reconectarse con /assistant/chat/trabajos/{id}/stream?desde=N.
    """
    limiter.hit(f"chat:{p.user.id}", 30)
    _, persona = _persona(db, p.user.id)
    if body.conversation_id:
        conv = db.get(Conversation, body.conversation_id)
        if conv is None or conv.user_id != p.user.id:
            raise not_found("conversación")
    else:
        conv = Conversation(user_id=p.user.id, title=body.message[:60])
        db.add(conv)
        db.flush()
    history = [assistant.Turn(role=m.role, content=open_text(m.content_enc, f"msg:{m.id}") or "")
               for m in db.execute(select(Message).where(Message.conversation_id == conv.id)
                                   .order_by(Message.created_at).limit(24)).scalars()]
    user_msg = Message(conversation_id=conv.id, role="user", content_enc=seal_text(body.message, "msg:pending"))
    db.add(user_msg)
    db.flush()
    user_msg.content_enc = seal_text(body.message, f"msg:{user_msg.id}")
    docs = _documentos(db, conv.id)
    doc_context, usados = lectura.contexto_para(body.message, docs) if docs else ("", [])
    imagenes = [open_text(a.image_enc, f"img:{a.id}") for a in db.execute(
        select(Attachment).where(Attachment.conversation_id == conv.id, Attachment.image_enc != "")
        .order_by(Attachment.created_at.desc()).limit(3)).scalars()]
    imagenes = [i for i in imagenes if i] if _modelo_ve_imagenes() else []
    lecciones = aprendizaje.bloque_para_prompt(db, p.user.id, body.message)
    conversation_id, user_id = conv.id, p.user.id
    db.commit()

    s = get_settings()
    allow_web = body.allow_web and s.web_browsing_enabled
    tipo_archivo = estudio.detectar_intencion(body.message) if body.crear_archivos else None

    def generar(t: chat_trabajos.Trabajo) -> None:
        """Corre en un hilo propio: sigue aunque el cliente se desconecte, y guarda al terminar."""
        piezas: list[str] = []
        reply = None
        creado_nombre = ""          # (antes se reiniciaba DESPUÉS de crear el archivo y se perdía)
        # Si la persona pidió un archivo, se crea antes de responder: así el chat entrega el
        # resultado y no una explicación de cómo hacerlo a mano.
        if tipo_archivo:
            try:
                t.agregar({"tipo": "creando", "formato": tipo_archivo})
                creado = _crear_archivo(body.message, tipo_archivo, user_id, allow_web)
                creado_nombre = creado["nombre"]
                t.agregar({"tipo": "archivo", **creado})
            except ApiError as e:
                t.agregar({"tipo": "error", "mensaje": f"No pude crear el archivo: {e.message}"})
            except Exception as e:  # noqa: BLE001 - un fallo aquí no debe tumbar la conversación
                log.exception("fallo creando archivo desde el asistente")
                t.agregar({"tipo": "error", "mensaje": f"No pude crear el archivo ({type(e).__name__}). "
                                                       "Puede pedírmelo de nuevo con más detalle."})
        prev = s.web_browsing_enabled
        if not allow_web:
            object.__setattr__(s, "web_browsing_enabled", False)
        try:
            if usados:
                t.agregar({"tipo": "archivos", "usados": usados})
            for evento in assistant.stream(body.message, history, persona, doc_context=doc_context,
                                           imagenes=imagenes, archivo_creado=creado_nombre,
                                           lecciones=lecciones):
                if t.cancelado:
                    t.agregar({"tipo": "cancelado", "mensaje": "Respuesta detenida"})
                    break
                if evento["tipo"] == "texto":
                    piezas.append(evento["texto"])
                if evento["tipo"] == "fin":
                    reply = evento["reply"]
                    t.agregar({"tipo": "fin", "conversation_id": conversation_id, "archivos_usados": usados,
                               "proveedor": reply.provider, "busco_en_web": reply.searched_web,
                               "fuentes": reply.sources, "acciones_sugeridas": reply.suggested_actions,
                               "seguridad": reply.safety, "latencia_ms": reply.latency_ms})
                else:
                    t.agregar(evento)
        finally:
            object.__setattr__(s, "web_browsing_enabled", prev)
            _guardar_respuesta(conversation_id, user_id, "".join(piezas), reply)

    try:
        trabajo = chat_trabajos.iniciar(user_id, conversation_id, generar)
    except chat_trabajos.Ocupado:
        raise ApiError(429, "chat_ocupado", "Ya hay 3 respuestas generándose. Espere a que termine alguna.")
    return _transmitir(trabajo, 0, primero={"tipo": "trabajo", "trabajo_id": trabajo.id,
                                             "conversation_id": conversation_id})


def _transmitir(trabajo: chat_trabajos.Trabajo, desde: int, primero: dict | None = None) -> StreamingResponse:
    """SSE que sigue un trabajo. Si el cliente se va, el trabajo NO se detiene: solo deja de mirarlo."""
    def eventos():
        if primero:
            yield _sse(primero)
        for i, ev in trabajo.seguir(desde):
            if ev is None:
                yield ": latido\n\n"          # mantiene viva la conexión en redes que cortan lo inactivo
            else:
                yield _sse({**ev, "i": i})

    return StreamingResponse(eventos(), media_type="text/event-stream", headers={
        "Cache-Control": "no-store",
        "X-Accel-Buffering": "no",  # evita que un proxy acumule la respuesta y anule el efecto
    })


@router.get("/assistant/chat/trabajos")
def trabajos_recientes(p: CurrentUser):
    """Respuestas en curso o recién terminadas: al volver a la app, el cliente sabe qué retomar."""
    return [t.resumen() for t in chat_trabajos.recientes(p.user.id)]


@router.get("/assistant/chat/trabajos/{trabajo_id}/stream")
def retomar_trabajo(trabajo_id: str, p: CurrentUser, desde: int = Query(0, ge=0)):
    """Reconecta a una respuesta: entrega los eventos desde ``desde`` y sigue en vivo si no terminó."""
    limiter.hit(f"chat-retomar:{p.user.id}", 60)      # reconexiones razonables, no un martilleo
    t = chat_trabajos.obtener(trabajo_id, p.user.id)
    if t is None:
        raise not_found("respuesta en curso")
    return _transmitir(t, desde)


@router.post("/assistant/chat/trabajos/{trabajo_id}/detener", response_model=Msg)
def detener_trabajo(trabajo_id: str, p: CurrentUser):
    """Detiene una respuesta: ya no basta con cerrar la conexión, porque la generación sigue sola."""
    if not chat_trabajos.cancelar(trabajo_id, p.user.id):
        raise not_found("respuesta en curso")
    return Msg(mensaje="Deteniendo la respuesta")


MODELOS_CON_VISION = ("llava", "vision", "gpt-4o", "gpt-4.1", "claude", "qwen2.5vl", "moondream", "minicpm-v",
                      "gemma3", "pixtral")


def _modelo_ve_imagenes() -> bool:
    """Solo se manda la imagen si el modelo puede verla; a uno de texto le llegaría basura."""
    s = get_settings()
    if s.llm_kind == "anthropic":
        return True
    nombre = (s.llm_model or "").lower()
    return any(marca in nombre for marca in MODELOS_CON_VISION)


def _crear_archivo(instruccion: str, tipo: str, user_id: str, buscar_web: bool = False) -> dict:
    """Genera el archivo y lo guarda cifrado en la bóveda. Devuelve lo que el chat debe mostrar.

    Cuando la búsqueda está activa, lo encontrado en internet se pasa al modelo para que el contenido
    tenga datos reales y no solo lo que el modelo recuerde.
    """
    from app.api.v1.estudio import _planificar
    contexto = ""
    if buscar_web:
        try:
            with httpx.Client(timeout=12.0) as client:
                contexto, _fuentes = assistant.gather_web_context(instruccion, client)
        except Exception:  # noqa: BLE001 - sin internet se crea igual, con lo que el modelo sabe
            contexto = ""
    plan, origen = _planificar(instruccion, tipo, contexto)
    try:
        creacion = estudio.construir(plan)
    except estudio.PlanInvalido:
        creacion = estudio.construir(estudio.plan_basico(instruccion, tipo))
        origen = "reglas"
    db = SessionLocal()
    try:
        from app.db.models import User
        usuario = db.get(User, user_id)
        f = boveda.guardar(db, usuario, categoria=creacion.categoria, nombre=creacion.nombre,
                           datos=creacion.datos, mime=creacion.mime)
        audit.record(db, "asistente.archivo_creado", actor_id=user_id, target_type="vault_file",
                     target_id=f.id, detail={"tipo": tipo, "plan": origen}, commit=False)
        db.commit()
        return {"archivo_id": f.id, "nombre": creacion.nombre, "formato": tipo, "bytes": len(creacion.datos),
                "resumen": creacion.resumen, "plan_de": origen,
                "descarga": f"/v1/vault/files/{f.id}/download"}
    finally:
        db.close()


def _sse(evento: dict) -> str:
    return f"data: {json.dumps(evento, ensure_ascii=False, default=str)}\n\n"


def _guardar_respuesta(conversation_id: str, user_id: str, texto: str, reply) -> None:
    """Guarda la respuesta con una sesión propia: la del request ya se cerró al empezar a transmitir."""
    if not texto.strip():
        return
    db = SessionLocal()
    try:
        conv = db.get(Conversation, conversation_id)
        if conv is None or conv.user_id != user_id:
            return
        msg = Message(conversation_id=conversation_id, role="assistant",
                      content_enc=seal_text(texto, "msg:pending"),
                      safety=getattr(reply, "safety", {}) or {},
                      provider=getattr(reply, "provider", "desconocido"),
                      latency_ms=getattr(reply, "latency_ms", 0))
        db.add(msg)
        db.flush()
        msg.content_enc = seal_text(texto, f"msg:{msg.id}")
        db.commit()
    finally:
        db.close()


@router.get("/assistant/conversations", response_model=list[ConversationOut])
def conversations(p: CurrentUser, db: DB, limit: int = Query(30, le=100)):
    return db.execute(select(Conversation).where(Conversation.user_id == p.user.id)
                      .order_by(Conversation.created_at.desc()).limit(limit)).scalars().all()


@router.get("/assistant/conversations/{conversation_id}", response_model=list[ChatMessageOut])
def conversation_messages(conversation_id: str, p: CurrentUser, db: DB):
    conv = db.get(Conversation, conversation_id)
    if conv is None or conv.user_id != p.user.id:
        raise not_found("conversación")
    rows = db.execute(select(Message).where(Message.conversation_id == conv.id)
                      .order_by(Message.created_at)).scalars().all()
    return [ChatMessageOut(role=m.role, content=open_text(m.content_enc, f"msg:{m.id}") or "",
                           created_at=m.created_at) for m in rows]


@router.delete("/assistant/conversations/{conversation_id}", response_model=Msg)
def delete_conversation(conversation_id: str, p: CurrentUser, db: DB):
    conv = db.get(Conversation, conversation_id)
    if conv is None or conv.user_id != p.user.id:
        raise not_found("conversación")
    db.delete(conv)
    db.commit()
    return Msg(mensaje="Conversación eliminada")


@router.get("/assistant/personality", response_model=PersonalityIO)
def get_personality(p: CurrentUser, db: DB):
    pp, _ = _persona(db, p.user.id)
    db.commit()
    return PersonalityIO(friendliness=pp.friendliness, protection=pp.protection, proactivity=pp.proactivity,
                         verbosity=pp.verbosity, humor=pp.humor, mode=pp.mode, assistant_name=pp.assistant_name)


@router.put("/assistant/personality", response_model=PersonalityIO)
def set_personality(body: PersonalityIO, p: CurrentUser, db: DB):
    pp, _ = _persona(db, p.user.id)
    for k, v in body.model_dump().items():
        setattr(pp, k, v)
    db.commit()
    return body


# ─────────────────────────────────────────────── lo que el asistente aprendió de la persona
class LeccionIn(BaseModel):
    cuando: str = Field("", max_length=400)
    haz: str = Field(..., min_length=3, max_length=400)


@router.get("/assistant/aprendizaje")
def lecciones(p: CurrentUser, db: DB):
    return {"lecciones": aprendizaje.listar(db, p.user.id), "maximo": aprendizaje.MAX_LECCIONES}


@router.post("/assistant/aprendizaje")
def ensenar(body: LeccionIn, p: CurrentUser, db: DB):
    limiter.hit(f"aprendizaje:{p.user.id}", 30)
    try:
        lec = aprendizaje.crear(db, p.user.id, body.cuando, body.haz)
    except ValueError as e:
        raise ApiError(422, "leccion_invalida", str(e)) from None
    return {"ok": True, "id": lec.id, "mensaje": "Aprendido. Lo aplicaré en preguntas parecidas."}


@router.delete("/assistant/aprendizaje/{leccion_id}", response_model=Msg)
def olvidar(leccion_id: str, p: CurrentUser, db: DB):
    if not aprendizaje.borrar(db, p.user.id, leccion_id):
        raise not_found("lección")
    return Msg(mensaje="Olvidado")
