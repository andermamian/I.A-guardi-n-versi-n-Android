import { ApiError } from "../../shared/api.js";
import { busy, confirmDialog, dialog, emptyState, errorText, field, fmt, h, icon, mount, pareceCodigo, richText, safeUrl, toast, toastError } from "../../shared/dom.js";
import { currentPosition } from "../components.js";

const TRAITS = [["friendliness", "Calidez"], ["protection", "Enfoque en protección"], ["proactivity", "Iniciativa"], ["verbosity", "Detalle en respuestas"], ["humor", "Humor"]];

export default async function asistente(ctx) {
  const { api } = ctx;
  let conversationId = ctx.params[0] || null;
  const [conversations, persona] = await Promise.all([api.get("assistant/conversations"), api.get("assistant/personality")]);

  const log = h("div", { class: "chat-log", role: "log", "aria-live": "polite" });
  const text = h("textarea", { class: "input", rows: 2, maxlength: 4000,
    placeholder: `Pregúntele a ${persona.assistant_name}`, "aria-label": "Mensaje" });
  // Modo código: la caja pasa a monoespaciada, Enter hace salto de línea (no envía) y al enviar el
  // texto va entre ``` para que el modelo lo lea como código y no como instrucciones.
  let modoCodigo = false;
  const lenguaje = h("select", { class: "input btn-sm", "aria-label": "Lenguaje del código", hidden: true },
    ["texto", "python", "javascript", "typescript", "java", "kotlin", "php", "sql", "html", "css", "bash",
     "c", "cpp", "csharp", "go", "rust", "ruby", "swift", "dart"].map((l) => h("option", { value: l === "texto" ? "" : l }, l)));
  const botonCodigo = h("button", { type: "button", class: "btn btn-sm", "aria-pressed": "false" },
    icon("copiar"), "Pegar código");
  botonCodigo.addEventListener("click", () => {
    modoCodigo = !modoCodigo;
    botonCodigo.setAttribute("aria-pressed", String(modoCodigo));
    lenguaje.hidden = !modoCodigo;
    text.classList.toggle("input-codigo", modoCodigo);
    text.rows = modoCodigo ? 8 : 2;
    text.placeholder = modoCodigo
      ? "Pegue o escriba su código aquí. Enter hace salto de línea; envíe con el botón o Ctrl+Enter."
      : `Pregúntele a ${persona.assistant_name}`;
    text.focus();
  });
  const web = h("input", { type: "checkbox", checked: true });
  const send = h("button", { class: "btn btn-primary", type: "submit" }, "Enviar");
  // Tarjeta del archivo que el asistente acaba de crear, con su descarga.
  function tarjetaArchivo(ctx, a) {
    const descargar = h("button", { class: "btn btn-primary btn-sm" }, icon("bajar"), "Descargar");
    descargar.addEventListener("click", () => busy(descargar, async () => {
      try { await ctx.api.download(a.descarga, a.nombre); } catch (e) { toastError(e); }
    }));
    return h("figure", { class: "archivo-creado" },
      icon("archivo"),
      h("div", { class: "list-main" },
        h("p", { class: "list-title" }, a.nombre),
        h("p", { class: "list-sub" }, `${a.resumen} · guardado cifrado en su bóveda`),
        a.plan_de === "reglas"
          ? h("p", { class: "muted" }, "Versión básica: el modelo no devolvió un plan válido. "
            + "Puede pedírmelo otra vez con más detalle.")
          : null),
      descargar);
  }

  // ───────────────────────────────────────────── archivos adjuntos
  const adjuntosEl = h("div", { class: "adjuntos" });
  const entradaArchivo = h("input", { type: "file", hidden: true, multiple: true,
    accept: ".pdf,.docx,.xlsx,.pptx,.csv,.txt,.md,.json,.png,.jpg,.jpeg,.webp,.py,.js,.java,.kt,.php,.sql,.html,.css" });
  const botonAdjuntar = h("button", { type: "button", class: "btn btn-sm" }, icon("subir"), "Adjuntar archivo");
  botonAdjuntar.addEventListener("click", () => entradaArchivo.click());
  entradaArchivo.addEventListener("change", () => {
    subirArchivos([...entradaArchivo.files]).finally(() => { entradaArchivo.value = ""; });
  });

  async function subirArchivos(archivos) {
    if (!archivos.length) return;
    await busy(botonAdjuntar, async () => {
      for (const archivo of archivos) {
        const chip = h("span", { class: "adjunto" }, icon("archivo"), archivo.name, h("small", {}, " leyendo…"));
        adjuntosEl.append(chip);
        const datos = new FormData();
        datos.append("file", archivo, archivo.name);
        if (conversationId) datos.append("conversation_id", conversationId);
        try {
          const r = await ctx.api.upload("assistant/attachments", datos);
          conversationId = conversationId || r.conversation_id;
          chip.replaceChildren(icon("archivo"), document.createTextNode(r.nombre),
            h("small", {}, ` ${detalleAdjunto(r)}`), botonQuitar(r.id, chip));
          if (r.truncado) toast(`${r.nombre} es muy largo: leí el comienzo. Pregúnteme por partes concretas.`, "info");
        } catch (e) {
          chip.remove();
          toast(`${archivo.name}: ${errorText(e)}`, "error", { timeout: 9000 });
        }
      }
    });
  }

  const detalleAdjunto = (r) => [r.paginas ? `${r.paginas} pág.` : null,
    r.caracteres > 1000 ? `${Math.round(r.caracteres / 1000)} mil caracteres` : `${r.caracteres} caracteres`,
    r.metodo].filter(Boolean).join(" · ");

  function botonQuitar(id, chip) {
    const b = h("button", { type: "button", class: "icon-btn", "aria-label": "Quitar archivo" }, icon("cerrar"));
    b.addEventListener("click", async () => {
      try { await ctx.api.del(`assistant/attachments/${id}`); chip.remove(); } catch (e) { toastError(e); }
    });
    return b;
  }

  async function cargarAdjuntos() {
    adjuntosEl.replaceChildren();
    if (!conversationId) return;
    try {
      const lista = await ctx.api.get(`assistant/conversations/${conversationId}/attachments`);
      for (const a of lista) {
        const chip = h("span", { class: "adjunto" }, icon("archivo"), a.nombre,
          h("small", {}, ` ${detalleAdjunto(a)}`));
        chip.append(botonQuitar(a.id, chip));
        adjuntosEl.append(chip);
      }
    } catch { /* conversación nueva */ }
  }

  // Arrastrar y soltar sobre el chat
  const zona = h("div", { class: "chat-zona" });
  zona.addEventListener("dragover", (e) => { e.preventDefault(); zona.dataset.over = ""; });
  zona.addEventListener("dragleave", () => delete zona.dataset.over);
  zona.addEventListener("drop", (e) => {
    e.preventDefault();
    delete zona.dataset.over;
    subirArchivos([...e.dataTransfer.files]);
  });

  const form = h("form", { class: "stack" }, text,
    h("div", { class: "row row-between" },
      h("div", { class: "row" },
        h("label", { class: "check" }, web, h("span", {}, "Permitir buscar en internet")),
        botonAdjuntar, botonCodigo, lenguaje),
      send));

  const bubble = (role, content, extra) => h("div", { class: `msg msg-${role}` }, content, extra || null);
  const sources = (list) => list?.length ? h("div", { class: "msg-sources" }, h("span", { class: "muted" }, "Fuentes:"),
    list.map((s) => h("a", { href: safeUrl(s.url), target: "_blank", rel: "noopener noreferrer nofollow" }, s.titulo || new URL(safeUrl(s.url), location.origin).hostname))) : null;

  async function openConversation(id) {
    conversationId = id;
    if (!id) {
      mount(log, emptyState(`Hola, soy ${persona.assistant_name}`,
        "Puedo revisar si un enlace es seguro, escribir código, crear documentos, explicarle una alerta de seguridad o ayudarle a navegar con privacidad."));
      return;
    }
    try {
      const msgs = await api.get(`assistant/conversations/${id}`);
      mount(log, msgs.map((m) => bubble(m.role, m.role === "assistant" ? richText(m.content) : m.content)));
      cargarAdjuntos();
      log.scrollTop = log.scrollHeight;
      retomarSiHay(id);
    } catch (e) { toastError(e); }
  }

  // Si al volver a esta conversación la IA sigue escribiendo (se cambió de chat o de pestaña), se retoma.
  async function retomarSiHay(id) {
    try {
      const trabajos = await api.get("assistant/chat/trabajos");
      const t = trabajos.find((x) => x.conversation_id === id && !x.terminado);
      if (t) {
        const vista = burbujaRespuesta();
        vista.estado.textContent = "Retomando la respuesta…";
        seguirTrabajo(t.trabajo_id, 0, vista);
      }
    } catch { /* sin trabajos que retomar */ }
  }

  function burbujaRespuesta() {
    const cuerpo = h("span", {}, "");
    const estado = h("p", { class: "muted msg-sources" }, "Pensando…");
    const respuesta = bubble("assistant", cuerpo, estado);
    respuesta.classList.add("msg-entra");
    respuesta.setAttribute("aria-busy", "true");
    const escribiendo = h("span", { class: "escribiendo", "aria-hidden": "true" }, h("i"), h("i"), h("i"));
    respuesta.insertBefore(escribiendo, estado);
    log.append(respuesta);
    log.scrollTop = log.scrollHeight;
    return { cuerpo, estado, respuesta, escribiendo };
  }

  const cerca = () => log.scrollHeight - log.scrollTop - log.clientHeight < 120;

  /** Lee un text/event-stream y entrega cada evento. Devuelve el último índice «i» recibido. */
  async function leerEventos(res, manejar, senal) {
    const lector = res.body.getReader();
    const decodificador = new TextDecoder();
    let pendiente = "";
    let ultimo = -1;
    senal?.addEventListener("abort", () => lector.cancel().catch(() => {}));
    for (;;) {
      const { value, done } = await lector.read();
      if (done) break;
      pendiente += decodificador.decode(value, { stream: true });
      const partes = pendiente.split("\n\n");
      pendiente = partes.pop();
      for (const parte of partes) {
        if (!parte.startsWith("data:")) continue;       // «: latido» y otros comentarios
        let evento;
        try { evento = JSON.parse(parte.slice(5)); } catch { continue; }
        if (Number.isInteger(evento.i)) ultimo = evento.i;
        manejar(evento);
      }
    }
    return ultimo;
  }

  async function peticionTransmision(url, opciones) {
    const pedir = () => fetch(url, {
      ...opciones,
      headers: { ...(opciones.headers || {}), Authorization: `Bearer ${api.token}`, Accept: "text/event-stream" },
      credentials: "same-origin",
    });
    let res = await pedir();
    if (res.status === 401) { await api.refresh(); res = await pedir(); }
    if (!res.ok || !res.body) throw new ApiError(res.status, await res.json().catch(() => ({})));
    return res;
  }

  /**
   * Sigue una respuesta en curso. Si la conexión se corta (red, pestaña en segundo plano), se reconecta
   * desde el último evento recibido: la IA nunca dejó de escribir en el servidor.
   */
  async function seguirTrabajo(trabajoId, desde, vista, res) {
    const { cuerpo, estado, respuesta, escribiendo } = vista;
    let fin = null, cancelado = false, siguiente = desde, intentos = 0;
    const control = new AbortController();
    ctx.onCleanup(() => control.abort());               // salir del chat solo deja de mirar
    const detener = h("button", { class: "btn btn-sm", onClick: async () => {
      detener.disabled = true;
      try { await api.post(`assistant/chat/trabajos/${trabajoId}/detener`, {}); } catch (e) { toastError(e); }
    } }, "Detener");
    estado.after(detener);
    const inicio = Date.now();
    const reloj = setInterval(() => {
      if (!cuerpo.textContent) estado.textContent = `Pensando… ${Math.round((Date.now() - inicio) / 1000)} s`;
    }, 1000);
    const manejar = (evento) => {
      if (evento.tipo === "preparando" && evento.busco_en_la_web) estado.textContent = "Buscando en internet…";
      if (evento.tipo === "creando") estado.textContent = `Creando el archivo (${evento.formato.toUpperCase()})…`;
      if (evento.tipo === "archivo") {
        respuesta.insertBefore(tarjetaArchivo(ctx, evento), cuerpo);
        estado.textContent = "Escribiendo la explicación…";
      }
      if (evento.tipo === "archivos") {
        estado.textContent = `Leyendo ${[...new Set(evento.usados.map((u) => u.documento))].join(", ")}…`;
      }
      if (evento.tipo === "texto") {
        const seguir = cerca();
        cuerpo.textContent += evento.texto;
        estado.textContent = "";
        escribiendo.hidden = true;
        if (seguir) log.scrollTop = log.scrollHeight;    // no arrastra la vista si la persona subió a leer
      }
      if (evento.tipo === "error") toast(evento.mensaje, "error");
      if (evento.tipo === "cancelado") cancelado = true;
      if (evento.tipo === "fin") fin = evento;
    };
    try {
      for (;;) {
        try {
          if (!res) res = await peticionTransmision(`/v1/assistant/chat/trabajos/${trabajoId}/stream?desde=${siguiente}`, { method: "GET" });
          const ultimo = await leerEventos(res, manejar, control.signal);
          if (ultimo >= 0) siguiente = ultimo + 1;
          res = null;
          if (fin || cancelado || control.signal.aborted) break;
        } catch (err) {
          res = null;
          if (control.signal.aborted || err?.status === 404) break;
          if (++intentos > 6) throw err;
        }
        if (!fin && !cancelado) {                         // se cortó: reconectar donde quedó
          estado.textContent = "Reconectando…";
          await new Promise((r) => setTimeout(r, Math.min(8000, 800 * intentos || 800)));
        }
      }
      if (fin) {
        // Se recompone con bloques de código y botón de copiar (el modelo a veces manda código sin ```).
        const completo = cuerpo.textContent;
        if (completo.trim()) cuerpo.replaceChildren(richText(completo));
        estado.replaceWith(h("div", {}, sources(fin.fuentes), actionButtons(ctx, fin.acciones_sugeridas)));
        if (fin.archivos_usados?.length) {
          const docs = [...new Set(fin.archivos_usados.map((u) => u.documento))];
          respuesta.append(h("p", { class: "msg-sources" }, `Basado en: ${docs.join(", ")}`));
        }
      } else if (cancelado) {
        const completo = cuerpo.textContent;
        if (completo.trim()) cuerpo.replaceChildren(richText(completo));
        estado.textContent = completo ? "Detenido." : "Cancelado.";
      } else if (!control.signal.aborted && !cuerpo.textContent) {
        estado.textContent = "La respuesta se interrumpió. Intente de nuevo.";
      }
    } catch (err) {
      estado.textContent = "";
      cuerpo.textContent = cuerpo.textContent || `No pude responder: ${errorText(err)}`;
    } finally {
      clearInterval(reloj);
      detener.remove();
      escribiendo.remove();
      respuesta.removeAttribute("aria-busy");
      if (estado.isConnected && !estado.textContent && !estado.firstChild) estado.remove();
      if (cerca()) log.scrollTop = log.scrollHeight;
    }
  }

  // Un modelo local en CPU tarda entre 20 s y varios minutos. La respuesta se genera en el servidor
  // como un «trabajo»: aunque se cambie de pestaña o de chat, sigue y queda guardada.
  async function enviar(message) {
    log.querySelector(".empty")?.remove();
    const mio = bubble("user", message.startsWith("```") ? richText(message) : message);
    mio.classList.add("msg-entra");
    log.append(mio);
    const vista = burbujaRespuesta();
    let res;
    try {
      res = await peticionTransmision("/v1/assistant/chat/stream", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message, conversation_id: conversationId, allow_web: web.checked }),
      });
    } catch (err) {
      vista.estado.textContent = "";
      vista.escribiendo.remove();
      vista.cuerpo.textContent = `No pude responder: ${errorText(err)}`;
      vista.respuesta.removeAttribute("aria-busy");
      text.value = text.value || message;
      return;
    }
    // El primer evento trae el trabajo: con él se puede reconectar si algo se corta.
    const lector = res.body.getReader();
    const primero = await lector.read();
    const texto = new TextDecoder().decode(primero.value || new Uint8Array());
    const linea = texto.split("\n\n").find((p) => p.startsWith("data:"));
    let trabajo = null;
    try { trabajo = linea ? JSON.parse(linea.slice(5)) : null; } catch { trabajo = null; }
    lector.cancel().catch(() => {});                    // desde aquí se sigue por el trabajo
    if (!trabajo?.trabajo_id) {
      vista.cuerpo.textContent = "La respuesta se interrumpió. Intente de nuevo.";
      return;
    }
    if (!conversationId) {
      conversationId = trabajo.conversation_id;
      history.replaceState(null, "", `#/asistente/${trabajo.conversation_id}`);
    }
    await seguirTrabajo(trabajo.trabajo_id, 0, vista);
  }

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const crudo = text.value.trim();
    if (!crudo) return;
    const message = modoCodigo ? `\`\`\`${lenguaje.value}\n${crudo}\n\`\`\`` : crudo;
    text.value = "";
    busy(send, () => enviar(message));
  });

  text.addEventListener("keydown", (e) => {
    if (e.key !== "Enter" || e.isComposing) return;
    // En modo código, Enter es salto de línea: escribir código con Enter que envía es inusable.
    if (e.ctrlKey || e.metaKey || (!modoCodigo && !e.shiftKey)) {
      e.preventDefault();
      form.requestSubmit();
    }
  });
  // Pegar algo que parece código activa el modo solo, sin que la persona tenga que pensarlo.
  text.addEventListener("paste", (e) => {
    const pegado = e.clipboardData?.getData("text") || "";
    if (!modoCodigo && pareceCodigo(pegado)) botonCodigo.click();
  });

  const side = h("aside", { class: "panel chat-side stack" },
    h("button", { class: "btn btn-primary", onClick: () => ctx.navigate("asistente") }, icon("mas_simbolo"), "Nueva conversación"),
    conversations.length ? h("ul", { class: "list" }, conversations.map((c) => h("li", {},
      h("a", { class: "list-main", href: `#/asistente/${c.id}` }, h("p", { class: "list-title" }, c.title), h("p", { class: "list-sub" }, fmt.ago(c.created_at))),
      h("button", { class: "icon-btn", "aria-label": "Eliminar conversación", onClick: async () => {
        if (!(await confirmDialog({ title: "Eliminar conversación", message: "Se borrará el historial cifrado de esta conversación.", danger: true, confirmLabel: "Eliminar" }))) return;
        try { await api.del(`assistant/conversations/${c.id}`); ctx.navigate("asistente"); } catch (e) { toastError(e); }
      } }, icon("papelera"))))) : h("p", { class: "muted" }, "Sus conversaciones aparecerán aquí."));

  const personaBtn = h("div", { class: "row" },
    h("button", { class: "btn", title: "Lo que el asistente aprendió de usted", onClick: () => aprendizajeDialog(ctx) }, "🧠 Enseñar"),
    h("button", { class: "btn", onClick: () => personalityDialog(ctx, persona) }, icon("ajustes"), "Personalidad"));
  await openConversation(conversationId);
  return h("div", { class: "stack" },
    h("header", { class: "page-head" }, h("div", {}, h("h1", {}, persona.assistant_name),
      h("p", {}, "Pregúntele de cualquier tema, pídale código en cualquier lenguaje o que busque en internet. Las conversaciones se guardan cifradas.")), personaBtn),
    h("div", { class: "chat" }, side,
      h("section", { class: "panel stack" }, log, adjuntosEl, entradaArchivo, form)));
}

// Acciones que propone el asistente. Nunca se ejecutan solas: llamar abre el marcador y el SOS pide confirmación.
function actionButtons(ctx, actions) {
  if (!actions?.length) return null;
  return h("div", { class: "row msg-sources" }, actions.map((a) => {
    if (a.tipo === "llamar" && /^\d{3,10}$/.test(a.numero)) return h("a", { class: "btn btn-sm btn-danger", href: `tel:${a.numero}` }, a.etiqueta);
    if (a.tipo === "confirmar" && a.accion === "activar_sos") {
      const b = h("button", { class: "btn btn-sm" }, a.etiqueta);
      b.addEventListener("click", async () => {
        if (!(await confirmDialog({ title: "Avisar a sus contactos", message: "Se enviará una alerta con su ubicación a sus contactos de emergencia.", confirmLabel: "Avisar ahora", danger: true }))) return;
        busy(b, async () => {
          try { await ctx.api.post("emergency/sos", { kind: "sos", note: "Solicitado desde el asistente", ...(await currentPosition()) }); toast("Alerta enviada a sus contactos", "ok"); }
          catch (e) { toastError(e); }
        });
      });
      return b;
    }
    const route = { escanear_aplicaciones: "proteccion", modo_perdido: "dispositivos", generar_contrasena: "boveda/claves" }[a.accion];
    if (a.tipo === "confirmar" && route) return h("a", { class: "btn btn-sm", href: `#/${route}` }, a.etiqueta);
    if (a.tipo === "resultado_url") {
      const tone = { segura: "badge-ok", sospechosa: "badge-warn", peligrosa: "badge-danger" }[a.veredicto] || "";
      return h("div", { class: "finding", dataset: { tone: a.veredicto === "peligrosa" ? "danger" : a.veredicto === "sospechosa" ? "warn" : null } },
        h("div", {}, h("p", {}, h("strong", {}, a.host), " ", h("span", { class: `badge ${tone}` }, a.veredicto)),
          a.motivos?.length ? h("p", { class: "muted" }, a.motivos.join("; ")) : null));
    }
    return null;
  }));
}

// «Cuando pida X, haz Y»: el servidor lo guarda cifrado y lo aplica solo en preguntas parecidas.
async function aprendizajeDialog(ctx) {
  const lista = h("div", { class: "stack" });
  async function pintar() {
    let datos;
    try { datos = await ctx.api.get("assistant/aprendizaje"); } catch (e) { toastError(e); return; }
    mount(lista, datos.lecciones.length ? datos.lecciones.map((l) => h("div", { class: "finding" },
      h("div", {}, h("p", {}, h("strong", {}, l.cuando || "Siempre"), " → ", l.aprendido),
        h("p", { class: "muted" }, l.usos ? `Aplicada ${l.usos} ${l.usos === 1 ? "vez" : "veces"}` : "Aún no se ha aplicado")),
      h("button", { class: "icon-btn", "aria-label": "Olvidar", onClick: async () => {
        try { await ctx.api.del(`assistant/aprendizaje/${l.id}`); pintar(); } catch (e) { toastError(e); }
      } }, icon("papelera")))) : h("p", { class: "muted" }, "Todavía no le ha enseñado nada."));
  }
  const form = h("form", { class: "form" },
    field("Cuando… (opcional)", h("input", { class: "input", name: "cuando", maxlength: 200, placeholder: "cuando pida un programa en Python" })),
    field("Haz esto", h("input", { class: "input", name: "haz", maxlength: 200, required: true, minlength: 3, placeholder: "incluye comentarios en español" })));
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!form.reportValidity()) return;
    try {
      await ctx.api.post("assistant/aprendizaje", { cuando: form.elements.namedItem("cuando").value.trim(), haz: form.elements.namedItem("haz").value.trim() });
      form.reset(); toast("Aprendido", "ok"); pintar();
    } catch (e) { toastError(e); }
  });
  form.append(h("button", { class: "btn btn-primary", type: "submit" }, "Enseñar"));
  dialog({ title: "Lo que el asistente aprendió de usted", body: h("div", { class: "stack" },
    h("p", { class: "muted" }, "Son preferencias: nunca cambian las reglas de seguridad del asistente."), form, lista),
    actions: [{ label: "Cerrar" }] });
  pintar();
}

function personalityDialog(ctx, p) {
  const form = h("form", { class: "form" },
    field("Nombre del asistente", h("input", { class: "input", name: "assistant_name", value: p.assistant_name, maxlength: 30, required: true })),
    TRAITS.map(([k, label]) => field(`${label}`, h("input", { type: "range", name: k, min: 0, max: 100, step: 5, value: p[k] }))),
    field("Modo", h("select", { class: "input", name: "mode" },
      [["home", "Casa"], ["work", "Trabajo"], ["emergency", "Emergencia (directo y breve)"]].map(([v, l]) => h("option", { value: v, selected: p.mode === v }, l)))));
  dialog({ title: "Personalidad", body: form, actions: [{ label: "Cancelar" }, { label: "Guardar", kind: "primary", onClick: async (close) => {
    if (!form.reportValidity()) return false;
    const body = { assistant_name: form.elements.namedItem("assistant_name").value.trim(), mode: form.elements.namedItem("mode").value };
    TRAITS.forEach(([k]) => { body[k] = Number(form.elements.namedItem(k).value); });
    try { await ctx.api.put("assistant/personality", body); toast("Personalidad guardada", "ok"); close(); ctx.refresh(); } catch (e) { toastError(e); }
    return false;
  } }] });
}
