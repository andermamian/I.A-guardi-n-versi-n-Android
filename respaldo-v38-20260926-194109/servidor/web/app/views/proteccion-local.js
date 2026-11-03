// Protección de ESTE equipo: antivirus con progreso en vivo, tiempo real, cuarentena y túnel VPN.
// Todo lo hace el propio servidor de GuardianIA: no hay agente que vincular ni comandos que copiar.
import { badge, busy, confirmDialog, emptyState, errorText, field, fmt, h, icon, mount, toast, toastError } from "../../shared/dom.js";

const NIVEL = { limpio: ["Limpio", "ok"], sospechoso: ["Sospechoso", "warn"], malicioso: ["Malicioso", "danger"] };
const MODO = { rapido: "Análisis rápido", completo: "Análisis completo", personalizado: "Análisis personalizado",
               usb: "Análisis de memoria USB" };
const ESTADO = { preparando: "Preparando", enumerando: "Buscando archivos", analizando: "Analizando",
                 pausado: "En pausa", cancelando: "Deteniendo", terminado: "Terminado", cancelado: "Detenido",
                 error: "Error" };
const ACTIVOS = new Set(["preparando", "enumerando", "analizando", "pausado", "cancelando"]);

const tiempo = (s) => {
  if (s == null) return "—";
  s = Math.max(0, Math.round(s));
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return hh ? `${hh} h ${String(mm).padStart(2, "0")} min` : mm ? `${mm} min ${String(ss).padStart(2, "0")} s` : `${ss} s`;
};
const tamano = (b) => b == null ? "—" : b >= 1073741824 ? `${(b / 1073741824).toFixed(1)} GB`
  : b >= 1048576 ? `${(b / 1048576).toFixed(0)} MB` : `${Math.round(b / 1024)} KB`;
// Rutas largas: se recorta el principio, porque lo que importa es el nombre del archivo.
const recortar = (ruta, max = 90) => ruta.length > max ? `…${ruta.slice(-max)}` : ruta;
const hace = (ts) => ts ? fmt.ago(new Date(ts * 1000).toISOString()) : "nunca";

function errorLocal(err) {
  if (err?.code === "solo_local") {
    return h("section", { class: "panel stack" }, h("h2", {}, "Solo desde el propio equipo"),
      h("p", {}, "La protección de un computador se controla sentado frente a él, en https://127.0.0.1:8443. "
        + "Así nadie en su red puede ordenar analizar o borrar archivos de este equipo."));
  }
  return h("p", { class: "notice notice-warn" }, err?.message || "No se pudo cargar la protección del equipo");
}

// ═══════════════════════════════════════════════════════ antivirus
export async function vistaAntivirus(ctx, cuerpo, actualizarResumen) {
  let estado;
  try {
    estado = await ctx.api.get("local/estado");
  } catch (e) {
    mount(cuerpo, errorLocal(e));
    return;
  }

  const monitorEl = h("div", {});
  const resumenEl = h("div", {});
  const profundoEl = h("div", {});
  const eventosEl = h("div", {});
  const cuarentenaEl = h("div", {});
  const ajustesEl = h("div", {});
  let temporizador = null;
  let ultimoId = null;
  let ultimoEstado = null;
  ctx.onCleanup(() => clearTimeout(temporizador));

  // ── lanzar análisis
  const rendimiento = h("select", { class: "input", "aria-label": "Rendimiento" },
    [["auto", "Automático: se aparta cuando usted trabaja"], ["turbo", "Máxima velocidad"],
     ["silencioso", "Silencioso: casi no se nota"]]
      .map(([v, l]) => h("option", { value: v, selected: estado.ajustes.rendimiento === v }, l)));
  const ruta = h("input", { class: "input", placeholder: "D:\\  o  C:\\Users\\usuario\\Documents",
    "aria-label": "Carpeta o unidad a analizar" });

  async function iniciar(modo, boton) {
    const cuerpo_ = { modo, rendimiento: rendimiento.value };
    if (modo === "personalizado") {
      if (!ruta.value.trim()) { ruta.focus(); return toast("Escriba la carpeta o unidad que quiere analizar", "error"); }
      cuerpo_.ruta = ruta.value.trim();
    }
    await busy(boton, async () => {
      try {
        pintarMonitor(await ctx.api.post("local/escaneo", cuerpo_));
        vigilar(true);
        monitorEl.scrollIntoView({ behavior: "smooth", block: "start" });
      } catch (e) { toastError(e); }
    });
  }
  const tarjetaModo = (modo, titulo, texto, primario) => {
    const b = h("button", { class: primario ? "btn btn-primary" : "btn" }, titulo);
    b.addEventListener("click", () => iniciar(modo, b));
    return h("div", { class: "modo-analisis" }, h("div", {}, h("h3", {}, titulo), h("p", { class: "muted" }, texto)), b);
  };
  const btnRuta = h("button", { class: "btn" }, "Analizar esta ruta");
  btnRuta.addEventListener("click", () => iniciar("personalizado", btnRuta));
  ruta.addEventListener("keydown", (e) => { if (e.key === "Enter") iniciar("personalizado", btnRuta); });

  // ── monitor del análisis en curso
  function pintarMonitor(s) {
    if (!s) {
      mount(monitorEl);
      return;
    }
    const activo = ACTIVOS.has(s.estado);
    const tono = s.maliciosos ? "danger" : s.sospechosos ? "warn" : "ok";
    const pct = Math.round(s.porcentaje * 10) / 10;

    const barra = h("span", {});
    barra.style.width = `${Math.max(pct, activo ? 1 : 0)}%`;
    const controles = h("div", { class: "row" });
    if (activo && s.estado !== "cancelando") {
      const pausa = h("button", { class: "btn btn-sm monitor-btn" }, s.estado === "pausado" ? "Reanudar" : "Pausar");
      pausa.addEventListener("click", () => busy(pausa, async () => {
        try { pintarMonitor(await ctx.api.post(`local/escaneo/${s.estado === "pausado" ? "reanudar" : "pausar"}`)); }
        catch (e) { toastError(e); }
      }));
      const detener = h("button", { class: "btn btn-sm monitor-btn" }, "Detener");
      detener.addEventListener("click", () => busy(detener, async () => {
        try { pintarMonitor(await ctx.api.post("local/escaneo/cancelar")); } catch (e) { toastError(e); }
      }));
      controles.append(pausa, detener);
    }

    const dato = (etiqueta, valor) => h("div", { class: "monitor-dato" },
      h("span", { class: "monitor-etiqueta" }, etiqueta), h("span", { class: "monitor-valor" }, valor));

    const total = s.total ?? s.descubiertos;
    mount(monitorEl, h("section", { class: "monitor-analisis", dataset: { estado: s.estado, tono },
      "aria-live": "polite" },
      h("div", { class: "monitor-cabeza" },
        h("div", {}, h("h2", {}, MODO[s.modo] || "Análisis"),
          h("p", {}, `${ESTADO[s.estado] || s.estado}${s.origen === "automatico" ? " (programado)" : ""}`
            + (s.estado === "pausado" || !activo ? "" : ` con ${s.hilos} de ${s.hilos_max} hilos`))),
        controles),
      h("div", { class: "monitor-cifra" },
        h("span", { class: "monitor-porcentaje" }, `${pct.toFixed(pct < 10 ? 1 : 0)} %`),
        h("span", { class: "monitor-sub" }, s.porcentaje_estimado && activo
          ? `${fmt.num(s.analizados)} analizados de ${fmt.num(s.descubiertos)} encontrados hasta ahora`
          : `${fmt.num(s.analizados)} de ${fmt.num(total || 0)} archivos`)),
      h("div", { class: "monitor-barra", role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100",
        "aria-valuenow": String(Math.round(pct)), "aria-label": "Progreso del análisis",
        dataset: { indeterminado: String(Boolean(s.porcentaje_estimado && activo)) } }, barra),
      h("p", { class: "monitor-archivo", title: s.archivo_actual || "" },
        activo ? (s.archivo_actual ? recortar(s.archivo_actual) : s.fase) : s.fase),
      h("div", { class: "monitor-datos" },
        dato("Transcurrido", tiempo(s.transcurrido_s)),
        dato("Restante", activo ? (s.eta_s != null ? tiempo(s.eta_s) : "calculando") : "—"),
        dato("Velocidad", activo ? `${fmt.num(s.velocidad_archivos_s)} arch/s, ${s.velocidad_mb_s} MB/s` : "—"),
        dato("Leídos", `${tamano(s.bytes_analizados)}${s.bytes_total ? ` de ${tamano(s.bytes_total)}` : ""}`),
        dato("Ya revisados antes", fmt.num(s.en_cache)),
        dato("CPU del equipo", activo ? `${Math.round(s.cpu_equipo)} % (otros programas ${Math.round(s.cpu_otros)} %)` : "—")),
      h("div", { class: "monitor-conteo" },
        h("span", { dataset: { tono: "ok" } }, `${fmt.num(s.limpios)} limpios`),
        h("span", { dataset: { tono: s.sospechosos ? "warn" : "" } }, `${fmt.num(s.sospechosos)} sospechosos`),
        h("span", { dataset: { tono: s.maliciosos ? "danger" : "" } }, `${fmt.num(s.maliciosos)} maliciosos`),
        h("span", {}, `${fmt.num(s.aislados)} en cuarentena`),
        s.omitidos ? h("span", {}, `${fmt.num(s.omitidos)} sin acceso`) : null),
      s.error ? h("p", { class: "notice notice-danger" }, s.error) : null,
      Array.isArray(s.amenazas) && s.amenazas.length
        ? h("div", { class: "monitor-amenazas stack" }, h("h3", {}, "Amenazas encontradas"),
          [...s.amenazas].reverse().slice(0, 40).map((a) => amenaza(a)))
        : !activo && s.estado === "terminado"
          ? h("p", { class: "monitor-limpio" }, "Ninguna capa de análisis encontró amenazas.")
          : null));
  }

  function amenaza(a) {
    const [etiqueta, tono] = NIVEL[a.nivel] || [a.nivel, "neutral"];
    const acciones = h("div", { class: "row" });
    if (a.estado === "aislado") acciones.append(badge("En cuarentena", "ok"));
    else if (a.estado === "no_aislado") acciones.append(badge("No se pudo aislar", "danger"));
    if (a.estado !== "aislado") {
      const aislar = h("button", { class: "btn btn-sm" }, "Poner en cuarentena");
      aislar.addEventListener("click", () => busy(aislar, async () => {
        try {
          toast((await ctx.api.post("local/cuarentena/aislar", { sha256: a.sha256 })).mensaje, "ok");
          a.estado = "aislado";
          cargarCuarentena();
          refrescar();
        } catch (e) { toastError(e); }
      }));
      acciones.append(aislar);
    }
    return h("div", { class: "finding", dataset: { tone: a.nivel === "malicioso" ? "danger" : "warn" } },
      h("div", { class: "stack-sm" },
        h("p", {}, h("strong", {}, a.nombre), " ", badge(etiqueta, tono),
          a.familia ? h("span", { class: "muted" }, ` ${a.familia}`) : null),
        h("p", { class: "mono muted" }, a.ruta),
        h("ul", {}, a.motivos.slice(0, 3).map((m) => h("li", {}, m))),
        a.estado === "detectado" && a.accion === "revisar"
          ? h("p", { class: "muted" }, "Solo hay señales heurísticas: no se aisló solo para no borrar un programa "
            + "legítimo por error. Si no reconoce el archivo, póngalo en cuarentena.")
          : null,
        acciones));
  }

  // Sondeo: rápido mientras hay análisis (la barra se mueve en vivo), lento en reposo para notar los
  // programados y los de USB.
  async function refrescar() {
    try {
      const s = await ctx.api.get("local/escaneo");
      pintarMonitor(s);
      const activo = s && ACTIVOS.has(s.estado);
      if (s && !activo && ultimoEstado && ACTIVOS.has(ultimoEstado) && s.id === ultimoId) {
        toast(s.maliciosos ? `Análisis terminado: ${s.maliciosos} amenaza(s)` : "Análisis terminado sin amenazas",
          s.maliciosos ? "error" : "ok");
        cargarCuarentena();
        cargarResumen(s);
        actualizarResumen?.();
      }
      ultimoId = s?.id; ultimoEstado = s?.estado;
      return activo;
    } catch { return false; }
  }
  async function cargarResumen(s) {
    if (!s || s.estado !== "terminado") { mount(resumenEl); return; }
    try {
      const r = await ctx.api.get("local/analisis/resumen");
      if (!r.hay) { mount(resumenEl); return; }
      const tono = s.maliciosos ? "danger" : s.sospechosos ? "warn" : "ok";
      mount(resumenEl, h("section", { class: "resumen-ia", dataset: { tono } },
        h("div", { class: "resumen-ia-icono" }, icon(s.maliciosos ? "alerta" : "check", { size: 24 })),
        h("div", { class: "stack-sm" },
          h("div", { class: "row", style: "gap:.5rem" }, h("strong", {}, "Resumen"),
            r.por_modelo ? badge("IA", "blue") : badge("Automático", "neutral")),
          h("p", {}, r.texto))));
    } catch { mount(resumenEl); }
  }

  function vigilar(inmediato = false) {
    clearTimeout(temporizador);
    const ciclo = async () => {
      const activo = await refrescar();
      if (!document.body.contains(monitorEl)) return;
      temporizador = setTimeout(ciclo, activo ? 500 : 4000);
    };
    if (inmediato) ciclo(); else temporizador = setTimeout(ciclo, 500);
  }

  // ── ajustes y tiempo real
  function pintarAjustes(e) {
    const conmutador = (campo, titulo, ayuda) => {
      const input = h("input", { type: "checkbox", checked: Boolean(e.ajustes[campo]) });
      input.addEventListener("change", async () => {
        try {
          const r = await ctx.api.put("local/ajustes", { [campo]: input.checked });
          e.ajustes = r.ajustes;
          toast(input.checked ? `${titulo}: activado` : `${titulo}: desactivado`, "ok");
          if (campo === "tiempo_real") cargarEventos();
        } catch (err) { input.checked = !input.checked; toastError(err); }
      });
      return h("label", { class: "check" }, input, h("span", {}, titulo, h("br"), h("small", { class: "muted" }, ayuda)));
    };
    const selector = (campo, opciones, etiqueta, ayuda) => {
      const s = h("select", { class: "input" }, opciones.map(([v, l]) =>
        h("option", { value: String(v), selected: Number(e.ajustes[campo]) === v }, l)));
      s.addEventListener("change", async () => {
        try { e.ajustes = (await ctx.api.put("local/ajustes", { [campo]: Number(s.value) })).ajustes; toast("Guardado", "ok"); }
        catch (err) { toastError(err); }
      });
      return field(etiqueta, s, ayuda);
    };
    const actualizar = h("button", { class: "btn btn-sm" }, "Actualizar firmas");
    actualizar.addEventListener("click", () => busy(actualizar, async () => {
      try {
        const r = await ctx.api.post("local/firmas/actualizar", {}, { timeout: 90000 });
        toast(r.mensaje, "ok");
        e.firmas = { ...e.firmas, total: r.total, actualizada: Date.now() / 1000 };
        pintarAjustes(e);
      } catch (err) { toastError(err); }
    }));

    const tr = e.tiempo_real;
    mount(ajustesEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" },
        h("div", {}, h("h2", {}, "Protección permanente"),
          h("p", {}, tr.activo ? `Vigilando ${tr.carpetas.length} carpetas, los programas que arrancan y las memorias USB`
            : "El tiempo real está apagado: solo se analiza cuando usted lo pide o según la programación")),
        badge(tr.activo ? "Activa" : "Apagada", tr.activo ? "ok" : "warn")),
      h("div", { class: "grid grid-2" },
        conmutador("tiempo_real", "Protección en tiempo real",
          "Revisa al instante cada descarga, programa que se abre y memoria USB que se conecta."),
        conmutador("cuarentena_automatica", "Cuarentena automática",
          "Aísla solo lo que tiene evidencia fuerte (firma conocida o confirmado por Defender)."),
        e.defender ? conmutador("segunda_opinion_defender", "Segunda opinión de Windows Defender",
          "Cuando GuardianIA duda de un archivo, se lo pregunta también a Defender.") : null),
      h("div", { class: "form-row" },
        selector("auto_rapido_horas", [[0, "Desactivado"], [3, "Cada 3 horas"], [6, "Cada 6 horas"], [12, "Cada 12 horas"],
          [24, "Una vez al día"]], "Análisis rápido automático", `Último: ${hace(e.ultimo_rapido)}`),
        selector("auto_completo_dias", [[0, "Desactivado"], [1, "Diario"], [7, "Semanal"], [15, "Cada 15 días"],
          [30, "Mensual"]], "Análisis completo automático", `Último: ${hace(e.ultimo_completo)}`)),
      h("div", { class: "row row-between" },
        h("p", { class: "muted" }, `Base de firmas: ${fmt.num(e.firmas.total)} huellas de malware conocido, `
          + `actualizada ${hace(e.firmas.actualizada)}. Motor ${e.motor}.`),
        actualizar),
      e.equipo.administrador ? null
        : h("p", { class: "muted" }, "El servidor corre sin permisos de administrador: se analizan sus carpetas y "
          + "los programas que usted puede leer. Es lo recomendable; la VPN pedirá permiso solo al conectar.")));
  }

  async function cargarEventos() {
    try {
      const r = await ctx.api.get("local/eventos");
      const lista = r.eventos.filter((e) => e.nivel !== "limpio").slice(0, 12);
      mount(eventosEl, h("section", { class: "panel" },
        h("div", { class: "panel-head" }, h("h2", {}, "Actividad en tiempo real"),
          h("span", { class: "muted" }, `${fmt.num(r.tiempo_real.analizados)} revisados desde que arrancó`)),
        lista.length
          ? h("ul", { class: "list" }, lista.map((e) => h("li", {},
            icon(e.nivel === "info" ? "check" : "alerta"),
            h("div", { class: "list-main" },
              h("p", { class: "list-title" }, e.texto),
              h("p", { class: "list-sub" }, [hace(e.hora), e.accion].filter(Boolean).join(", "))),
            NIVEL[e.nivel] ? badge(...NIVEL[e.nivel]) : null)))
          : emptyState("Sin novedades", "Cuando algo llegue a Descargas, arranque o se conecte una USB, aparecerá aquí.")));
    } catch { /* secundario */ }
  }

  async function cargarCuarentena() {
    try {
      const filas = (await ctx.api.get("local/cuarentena")).filter((f) => f.estado === "en_cuarentena");
      mount(cuarentenaEl, h("section", { class: "panel" },
        h("div", { class: "panel-head" }, h("div", {}, h("h2", {}, "Cuarentena"),
          h("p", {}, "Archivos cifrados fuera de su carpeta: no pueden ejecutarse. Usted decide qué hacer."))),
        filas.length ? tablaCuarentena(filas)
          : emptyState("Cuarentena vacía", "Lo que se aísle aparecerá aquí, con el motivo.")));
    } catch { /* secundario */ }
  }

  function tablaCuarentena(filas) {
    return h("div", { class: "table-wrap" }, h("table", { class: "table" },
      h("thead", {}, h("tr", {}, ["Archivo", "Amenaza", "Fecha", ""].map((t) => h("th", {}, t)))),
      h("tbody", {}, filas.map((f) => {
        const restaurar = h("button", { class: "btn btn-sm" }, "Restaurar");
        restaurar.addEventListener("click", async () => {
          const ok = await confirmDialog({
            title: "Restaurar el archivo", danger: f.nivel === "malicioso", confirmLabel: "Restaurar",
            message: `${f.archivo} volverá a ${f.ruta} y no se volverá a marcar. `
              + (f.nivel === "malicioso" ? "Fue clasificado como malicioso: hágalo solo si está seguro de que es "
                + "un falso positivo." : ""),
          });
          if (!ok) return;
          try { toast((await ctx.api.post(`local/cuarentena/${f.id}/restaurar`)).mensaje, "ok"); cargarCuarentena(); }
          catch (e) { toastError(e); }
        });
        const eliminar = h("button", { class: "btn btn-sm btn-danger" }, "Eliminar");
        eliminar.addEventListener("click", async () => {
          const ok = await confirmDialog({ title: "Eliminar definitivamente", danger: true, confirmLabel: "Eliminar",
            message: `${f.archivo} se borrará sin posibilidad de recuperarlo.` });
          if (!ok) return;
          try { toast((await ctx.api.post(`local/cuarentena/${f.id}/eliminar`)).mensaje, "ok"); cargarCuarentena(); }
          catch (e) { toastError(e); }
        });
        return h("tr", {},
          h("td", {}, h("strong", {}, f.archivo), h("br"), h("small", { class: "mono muted" }, f.ruta)),
          h("td", {}, badge(...(NIVEL[f.nivel] || [f.nivel, "neutral"])),
            f.familia ? h("div", { class: "muted" }, f.familia) : null,
            f.motivos?.length ? h("details", {}, h("summary", { class: "muted" }, "Por qué"),
              h("ul", {}, f.motivos.map((m) => h("li", {}, m)))) : null),
          h("td", {}, fmt.date(f.creado)),
          h("td", {}, h("div", { class: "row" }, restaurar, eliminar)));
      }))));
  }


  async function cargarProfundo() {
    const boton = h("button", { class: "btn btn-sm" }, "Revisar sistema y red");
    const cont = h("div", {});
    boton.addEventListener("click", () => busy(boton, async () => {
      try {
        const r = await ctx.api.get("local/revision-profunda", {}, { timeout: 60000 });
        pintarProfundo(cont, r);
      } catch (e) { toastError(e); }
    }));
    mount(profundoEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" },
        h("div", {}, h("h2", {}, "Sistema y red (revisión profunda)"),
          h("p", {}, "Drivers sin firma, procesos suplantados, cambios en el arranque, conexiones y DNS "
            + "sospechosos. Observa el kernel desde afuera; para verlo desde dentro haría falta un driver "
            + "firmado por Microsoft, que este programa no tiene.")),
        boton),
      cont));
  }

  function pintarProfundo(cont, r) {
    const tono = r.estado === "peligro" ? "danger" : r.estado === "revisar" ? "warn" : "ok";
    const bloques = [];
    const seccion = (titulo, data) => {
      const items = (data.hallazgos || []).map((h_) => h("div", {
        class: "finding", dataset: { tone: h_.nivel === "peligroso" ? "danger" : "warn" } },
        h("div", { class: "stack-sm" },
          h("p", {}, h("strong", {}, h_.titulo), " ", badge(h_.nivel === "peligroso" ? "Peligroso" : "Revisar",
            h_.nivel === "peligroso" ? "danger" : "warn")),
          h("p", { class: "muted" }, h_.detalle))));
      return h("div", { class: "stack-sm" },
        h("h3", {}, titulo), h("p", { class: "muted" }, data.resumen),
        items.length ? h("div", { class: "stack-sm" }, items) : null);
    };
    mount(cont,
      h("div", { class: "resumen-ia", dataset: { tono } },
        h("div", { class: "resumen-ia-icono" }, icon(r.estado === "limpio" ? "check" : "alerta", { size: 22 })),
        h("div", { class: "stack-sm" }, h("strong", {}, "Resultado"), h("p", {}, r.resumen))),
      h("div", { class: "grid grid-2", style: "margin-top:1rem" },
        seccion("Núcleo del sistema", r.kernel),
        seccion("Red y DNS", r.red)));
  }

  mount(cuerpo,
    h("section", { class: "panel stack" },
      h("h2", {}, "Analizar este equipo"),
      h("div", { class: "grid grid-3" },
        tarjetaModo("rapido", "Análisis rápido", "Programas en ejecución, programas de inicio, Descargas, Escritorio "
          + "y temporales. Unos minutos.", true),
        tarjetaModo("completo", "Análisis completo", `Todos los discos: ${estado.equipo.unidades.join(", ") || "—"}. `
          + "El primero tarda; los siguientes solo revisan lo que cambió."),
        h("div", { class: "modo-analisis" },
          h("div", {}, h("h3", {}, "Carpeta o memoria USB"), h("p", { class: "muted" }, "Escriba la ruta completa.")),
          ruta, btnRuta)),
      field("Rendimiento", rendimiento, `Su equipo tiene ${estado.equipo.nucleos} hilos de CPU.`)),
    resumenEl, profundoEl, monitorEl, ajustesEl, eventosEl, cuarentenaEl);

  pintarAjustes(estado);
  cargarEventos();
  cargarCuarentena();
  cargarProfundo();
  vigilar(true);
}

// ═══════════════════════════════════════════════════════ túnel VPN
const bytes = (n) => n == null ? "—" : n >= 1073741824 ? `${(n / 1073741824).toFixed(2)} GB`
  : n >= 1048576 ? `${(n / 1048576).toFixed(1)} MB` : n >= 1024 ? `${(n / 1024).toFixed(0)} KB` : `${n} B`;
const reloj = (s) => {
  if (!s) return "0 s";
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return hh ? `${hh}:${String(mm).padStart(2, "0")}:${String(ss).padStart(2, "0")}`
    : `${mm}:${String(ss).padStart(2, "0")}`;
};

export async function panelTunelLocal(ctx, alCambiar) {
  const raiz = h("div", { class: "stack" });
  const conexionEl = h("div", {});
  const statsEl = h("div", {});
  const servidoresEl = h("div", {});
  const anonEl = h("div", {});
  const torEl = h("div", {});
  const ubicEl = h("div", {});
  const botonEl = h("div", {});
  const propioEl = h("div", {});
  let timerBoton = null;
  ctx.onCleanup(() => clearTimeout(timerBoton));
  const bandera = (cc) => (cc && /^[A-Z]{2}$/.test(cc))
    ? String.fromCodePoint(...[...cc].map((c) => 0x1F1E6 + c.charCodeAt(0) - 65)) : "🌐";
  let timerTor = null;
  ctx.onCleanup(() => clearTimeout(timerTor));
  let timerStats = null;
  let ultimoEstado = null;
  ctx.onCleanup(() => clearTimeout(timerStats));

  async function pintar(e) {
    if (!e) {
      try { e = await ctx.api.get("local/vpn"); } catch (err) { mount(raiz, errorLocal(err)); return; }
    }
    ultimoEstado = e;
    pintarConexion(e);
    if (e.activo) vigilarStats(true); else { clearTimeout(timerStats); mount(statsEl); }
    if (e.activo) pintarAnonimato(false); else mount(anonEl);
    // v3.5: una sola vía de VPN (ProtonVPN, una ubicación por país) + Tor. WARP y servidores propios retirados.
    mount(servidoresEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" }, h("h2", {}, "Agregar ubicaciones de ProtonVPN")),
      origenesImport(e)));
    mount(raiz, botonEl, propioEl, conexionEl, statsEl, ubicEl, anonEl, torEl, servidoresEl);
    pintarBoton();
    pintarPropio();
    pintarUbicaciones();
    pintarTor();
  }

  // ── privacidad con UN botón (pasarela: VPN + Tor, dos objetivos, vigilante automático)
  let modoSel = "proton_tor", objetivoSel = "anonimato";
  async function pintarBoton() {
    clearTimeout(timerBoton);
    let st;
    try { st = await ctx.api.get("local/privacidad", { timeout: 20000 }); } catch { mount(botonEl); return; }
    const encendido = st.modo !== "apagado";
    if (encendido) { modoSel = st.modo; objetivoSel = st.objetivo; }
    const chip = (valor, texto, actual, fijar) => {
      const b = h("button", { class: `btn btn-sm ${valor === actual ? "btn-primary" : ""}`, type: "button" }, texto);
      b.addEventListener("click", () => { fijar(valor); if (encendido) activar(); else pintarBoton(); });
      return b;
    };
    const principal = h("button", { class: `btn btn-lg ${encendido ? "btn-danger" : "btn-primary"}` },
      encendido ? "Apagar privacidad" : "Activar privacidad");
    const activar = () => busy(principal, async () => {
      if (!encendido || principal.textContent !== "Apagar privacidad") {
        toast("Si Windows pide permiso de administrador para el túnel, acéptelo.", "info", { timeout: 7000 });
      }
      try {
        const r = await ctx.api.put("local/privacidad", { modo: modoSel, objetivo: objetivoSel }, { timeout: 180000 });
        if (r.razon) toast(`GuardianIA: ${r.razon}`, "ok", { timeout: 9000 });
        pintar();
        alCambiar?.();
      } catch (err) { toastError(err); pintarBoton(); }
    });
    principal.addEventListener("click", () => {
      if (encendido) {
        busy(principal, async () => {
          try { await ctx.api.put("local/privacidad", { modo: "apagado" }, { timeout: 120000 }); pintar(); alCambiar?.(); }
          catch (err) { toastError(err); }
        });
      } else activar();
    });
    const u = st.ubicacion;
    const relevos = st.tor?.circuito || [];
    const cadena = relevos.length ? relevos.map((r, i) => `${["Entrada", "Medio", "Salida"][i] || "Relevo"} ${bandera(r.pais)}`).join(" → ") : "";
    const nueva = h("button", { class: "btn btn-sm" }, "Nueva identidad Tor");
    nueva.addEventListener("click", () => busy(nueva, async () => {
      try { await ctx.api.post("local/privacidad/nueva-identidad", {}); toast("Circuitos Tor renovados: los sitios verán otra IP.", "ok"); pintarBoton(); }
      catch (err) { toastError(err); }
    }));
    const tono = st.protegido ? "ok" : encendido ? "warn" : "idle";
    mount(botonEl, h("section", { class: "panel stack privacidad", dataset: { tono } },
      h("div", { class: "panel-head" },
        h("div", {}, h("h2", {}, st.protegido ? `Protegido · ${{ proton: "VPN", tor: "Tor", proton_tor: "VPN + Tor" }[st.modo]}`
          : encendido ? "Conectando…" : "Privacidad"),
          h("p", { class: "muted" }, st.protegido
            ? [u ? `${bandera(u.pais)} ${u.pais_nombre}${u.propio ? " · su servidor GuardianIA" : ""}` : "",
              st.tor?.listo ? `Tor ${st.tor.aislamiento ? "· un circuito por sitio" : ""}` : "",
              st.anonimato ? `Anonimato ${st.anonimato.nivel} (${st.anonimato.puntaje}/100)` : ""].filter(Boolean).join(" · ")
            : "Un botón: GuardianIA elige la ubicación, conecta, verifica que su IP cambió y vigila cada 30 s.")),
        h("span", { class: "privacidad-orbe", "aria-hidden": "true" })),
      h("div", { class: "row" }, h("span", { class: "muted" }, "Modo:"),
        chip("proton", "VPN", modoSel, (v) => { modoSel = v; }), chip("tor", "Tor", modoSel, (v) => { modoSel = v; }),
        chip("proton_tor", "VPN + Tor (recomendado)", modoSel, (v) => { modoSel = v; })),
      h("div", { class: "row" }, h("span", { class: "muted" }, "Objetivo:"),
        chip("velocidad", "Velocidad", objetivoSel, (v) => { objetivoSel = v; }),
        chip("anonimato", "Anonimato máximo", objetivoSel, (v) => { objetivoSel = v; })),
      h("p", { class: "muted" }, objetivoSel === "anonimato"
        ? "Anonimato máximo: ubicación fuera de las alianzas de vigilancia, Tor con un circuito por sitio y modo estricto (si Tor cae, GuardianIA no sale a internet)."
        : "Velocidad: la ubicación con menor latencia medida (su servidor propio primero) y Tor reutilizando circuitos."),
      st.tor?.activo && !st.tor?.listo ? h("div", { class: "progreso-tor" }, h("span", { style: `width:${Math.max(4, st.tor.progreso || 0)}%` })) : null,
      cadena ? h("p", { class: "muted" }, `Circuito Tor: ${cadena}`) : null,
      st.ultimo?.evento === "reconectado" || st.ultimo?.evento === "tor_reiniciado"
        ? h("p", { class: "notice" }, `Vigilante: ${st.ultimo.razon}`) : st.ultimo?.evento === "fallo" ? h("p", { class: "notice notice-warn" }, st.ultimo.razon) : null,
      h("div", { class: "row" }, principal, st.tor?.listo ? nueva : null)));
    if (encendido) timerBoton = setTimeout(() => { if (document.body.contains(botonEl)) pintarBoton(); }, 3000);
  }

  // ── su servidor VPN propio de GuardianIA (Google Cloud): se conecta UNA vez con 3 datos
  async function pintarPropio() {
    let e;
    try { e = await ctx.api.get("local/vpn/propio", { timeout: 25000 }); } catch { mount(propioEl); return; }
    if (!e.configurado) {
      const url = h("input", { class: "input", placeholder: "https://34.123.45.67:8844", autocomplete: "off" });
      const huella = h("input", { class: "input", placeholder: "AB:CD:EF:… (64 pares)", autocomplete: "off" });
      const ficha = h("input", { class: "input", type: "password", placeholder: "Ficha secreta", autocomplete: "off" });
      const conectar = h("button", { class: "btn btn-primary" }, "Conectar mi servidor");
      conectar.addEventListener("click", () => busy(conectar, async () => {
        try {
          const r = await ctx.api.put("local/vpn/propio", { url: url.value.trim(), huella: huella.value.trim(), ficha: ficha.value.trim() }, { timeout: 60000 });
          ficha.value = "";
          toast(`Servidor conectado (${bandera(r.pais)} ${r.region}). Este PC ya está dado de alta; en el celular: Seguridad → VPN → «Usar mi servidor GuardianIA».`, "ok", { timeout: 12000 });
          pintar();
        } catch (err) { toastError(err); }
      }));
      mount(propioEl, h("section", { class: "panel stack" },
        h("div", { class: "panel-head" }, h("h2", {}, "Mi servidor GuardianIA VPN")),
        h("p", { class: "muted" }, "Pegue los 3 datos que mostró el instalador en su máquina de Google Cloud. Se hace UNA vez: "
          + "después su PC y su celular se dan de alta solos, sin archivos."),
        field("Dirección de la API", url), field("Huella del certificado (SHA-256)", huella), field("Ficha secreta", ficha),
        h("p", { class: "muted" }, "Seguridad: GuardianIA comprueba la huella ANTES de enviar la ficha, la guarda cifrada y el celular nunca la recibe."),
        conectar));
      return;
    }
    const quitar = h("button", { class: "btn btn-sm btn-ghost" }, "Desconectar este servidor");
    quitar.addEventListener("click", async () => {
      if (!(await confirmDialog({ title: "Desconectar el servidor", danger: true, confirmLabel: "Desconectar",
        message: "GuardianIA olvidará la ficha y la ubicación del servidor en este PC. El servidor en Google Cloud sigue existiendo." }))) return;
      try { await ctx.api.del("local/vpn/propio"); pintar(); } catch (err) { toastError(err); }
    });
    const c = e.consumo;
    const pct = c ? Math.min(100, Math.round((c.usado_gb / c.limite_gb) * 100)) : 0;
    mount(propioEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" }, h("h2", {}, `Mi servidor GuardianIA VPN ${bandera(e.pais)}`),
        h("span", { class: `badge ${e.en_linea ? "badge-ok" : "badge-warn"}` }, e.en_linea ? "En línea" : "Sin respuesta")),
      h("p", { class: "muted" }, `${e.region || ""} · ${e.endpoint || ""}${e.dispositivos != null ? ` · ${e.dispositivos} dispositivo(s)` : ""}`),
      e.error ? h("p", { class: "notice notice-warn" }, e.error) : null,
      c ? h("div", { class: "stack-sm" },
        h("p", { class: "muted" }, `Consumo del mes: ${c.usado_gb} GB de ${c.limite_gb} GB gratis${c.frenado ? " — FRENADO hasta el próximo mes (para que no le cobren)" : ""}`),
        h("div", { class: "progreso-tor" }, h("span", { style: `width:${Math.max(2, pct)}%` }))) : null,
      h("div", { class: "row" }, quitar)));
  }

  // ── cabecera: estado + acciones (Desconectar SIEMPRE visible si hay túnel)
  function pintarConexion(e) {
    const conectado = e.activo;
    const punto = conectado ? (e.verificado ? "conectada" : "sin_verificar") : "desconectado";
    const acciones = h("div", { class: "row" });

    if (!e.wireguard_instalado) {
      acciones.append(h("a", { class: "btn btn-primary", href: "https://www.wireguard.com/install/",
        target: "_blank", rel: "noopener noreferrer" }, "Descargar WireGuard"));
    } else if (conectado) {
      const cortar = h("button", { class: "btn btn-danger btn-lg" }, icon("bajar"), "Desconectar");
      cortar.addEventListener("click", () => busy(cortar, async () => {
        try {
          const r = await ctx.api.post("local/vpn/desconectar", {}, { timeout: 120000 });
          toast(r.mensaje, "ok");
          pintar(r);
          alCambiar?.();
        } catch (err) { toastError(err); }
      }));
      acciones.append(cortar);
    } else if (e.perfil) {
      const conectar = h("button", { class: "btn btn-primary btn-lg" }, icon("servidor"), "Conectar");
      conectar.addEventListener("click", () => conectarAhora(conectar));
      acciones.append(conectar);
    }

    const titulo = conectado ? (e.verificado ? "VPN conectada y verificada" : "Túnel instalado, sin verificar")
      : e.perfil ? "Listo para conectar" : "VPN desconectada";
    const detalle = conectado
      ? (e.verificado ? `Su tráfico sale por ${e.pais_salida || "?"} con la IP ${e.ip_salida}.`
        : "El túnel se instaló pero su IP no ha cambiado. Espere unos segundos o presione Desconectar y reintente.")
      : e.perfil ? `${e.perfil.nombre} (${e.perfil.endpoint || "listo"}). Presione Conectar.`
        : "Elija abajo cómo quiere conectarse.";

    mount(conexionEl, h("section", { class: "panel stack" },
      h("div", { class: "vpn-panel" },
        h("div", { class: "vpn-estado", dataset: { estado: punto } }, h("span", { class: "vpn-punto" }),
          h("div", {}, h("h2", {}, titulo), h("p", { class: "muted" }, detalle))),
        acciones),
      !e.wireguard_instalado
        ? h("p", { class: "notice notice-warn" }, "Falta WireGuard, el programa que crea el túnel cifrado. Es "
          + "gratis, se instala como cualquier programa y luego recargue esta página.")
        : null));
  }

  async function conectarAhora(boton) {
    toast("Windows pedirá permiso de administrador para crear el túnel. Acepte la ventana.", "info", { timeout: 9000 });
    await busy(boton, async () => {
      try {
        const r = await ctx.api.post("local/vpn/conectar", {}, { timeout: 150000 });
        toast(r.mensaje, r.verificado ? "ok" : "error", { timeout: 9000 });
        pintar(r);
        alCambiar?.();
      } catch (err) {
        // Si WireGuard dice que el túnel ya está corriendo, no es un fallo: refrescamos el estado.
        const msg = (err?.message || "").toLowerCase();
        if (msg.includes("already") || msg.includes("ya está") || msg.includes("running")) {
          toast("El túnel ya estaba activo. Mostrando su estado.", "ok");
          const est = await ctx.api.get("local/vpn").catch(() => null);
          pintar(est || undefined);
          alCambiar?.();
        } else {
          toastError(err);
        }
      }
    });
  }

  // ── ubicaciones: varios .conf (uno por país); se cambia de país con un clic
  async function pintarUbicaciones() {
    let lista;
    try { lista = await ctx.api.get("local/vpn/ubicaciones"); } catch { mount(ubicEl); return; }
    if (!lista.length) { mount(ubicEl); return; }
    const fila = (u) => {
      const usar = h("button", { class: u.en_uso ? "btn btn-sm" : "btn btn-sm btn-primary", disabled: u.en_uso },
        u.en_uso ? "En uso" : "Conectar desde aquí");
      usar.addEventListener("click", () => busy(usar, async () => {
        try {
          const r = await ctx.api.post(`local/vpn/ubicaciones/${u.id}/usar`, {}, { timeout: 120000 });
          toast(r.reconectar === false ? `Ubicación ${u.pais_nombre} lista: presione Conectar.`
            : `Conectado desde ${u.pais_nombre}.`, "ok");
          pintar();
        } catch (err) { toastError(err); }
      }));
      const quitar = h("button", { class: "btn btn-sm btn-ghost", "aria-label": `Eliminar ${u.nombre}` }, "Eliminar");
      quitar.addEventListener("click", () => busy(quitar, async () => {
        try { await ctx.api.del(`local/vpn/ubicaciones/${u.id}`); pintarUbicaciones(); } catch (err) { toastError(err); }
      }));
      return h("li", {}, h("div", { class: "list-main" },
        h("p", { class: "list-title" }, `${u.propio ? "🛡 Tu servidor GuardianIA · " : ""}${bandera(u.pais)} ${u.pais_nombre}${u.verificado ? " ✓" : ""}`),
        h("p", { class: "list-sub" }, `${u.nombre} · ${u.endpoint || ""}`)),
        h("div", { class: "row" }, usar, quitar));
    };
    mount(ubicEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" }, h("h2", {}, "Tus ubicaciones"),
        h("span", { class: "muted" }, `${lista.length} país(es)`)),
      h("p", { class: "muted" }, "Elige desde qué país salir. ✓ = país confirmado al conectar (lo que ven los sitios). "
        + "Las plataformas de streaming suelen bloquear las VPN gratuitas; noticias y tiendas sí funcionan."),
      h("ul", { class: "list" }, lista.map(fila))));
  }

  // ── modo cebolla (Tor): gratis, con el Tor oficial; se verifica con el Tor Project
  async function pintarTor(verificar = false) {
    clearTimeout(timerTor);
    let t;
    try { t = await ctx.api.get(`local/tor?verificar=${verificar ? "true" : "false"}`, { timeout: 40000 }); }
    catch { mount(torEl); return; }
    const acciones = h("div", { class: "row" });
    if (!t.activo) {
      const iniciar = h("button", { class: "btn btn-primary" }, "Activar Tor");
      iniciar.addEventListener("click", () => busy(iniciar, async () => {
        try { await ctx.api.post("local/tor/iniciar", {}); pintarTor(); } catch (err) { toastError(err); }
      }));
      acciones.append(iniciar);
      if (!t.instalado) acciones.append(h("a", { class: "btn", href: "https://www.torproject.org/download/",
        target: "_blank", rel: "noopener noreferrer" }, "Descargar Tor Browser (gratis)"));
    } else {
      if (t.listo) {
        const probar = h("button", { class: "btn" }, "Verificar con el Tor Project");
        probar.addEventListener("click", () => busy(probar, () => pintarTor(true)));
        acciones.append(probar);
      }
      if (t.origen === "guardian") {
        const apagar = h("button", { class: "btn btn-danger" }, "Apagar Tor");
        apagar.addEventListener("click", () => busy(apagar, async () => {
          try { await ctx.api.post("local/tor/detener", {}); pintarTor(); } catch (err) { toastError(err); }
        }));
        acciones.append(apagar);
      }
    }
    // Cómo salen a internet los módulos de GuardianIA (búsqueda web, filtraciones, firmas).
    let red = null;
    try { red = await ctx.api.get("local/tor/red"); } catch { red = null; }
    const selector = red ? h("select", { class: "input", "aria-label": "Tráfico de GuardianIA por Tor" },
      [["apagado", "Directo (sin Tor)"], ["preferir", "Por Tor si está activo"], ["estricto", "Solo por Tor (estricto: sin Tor no sale nada)"]]
        .map(([v, txt]) => h("option", { value: v, selected: red.modo === v }, txt))) : null;
    selector?.addEventListener("change", async () => {
      try { red = await ctx.api.put("local/tor/red", { modo: selector.value }); toast("Modo de red guardado", "ok"); pintarTor(); }
      catch (err) { toastError(err); }
    });
    const paisSel = t.pais ? h("select", { class: "input", "aria-label": "País de salida de Tor" },
      [h("option", { value: "", selected: !t.pais.elegido }, "🌐 Cualquiera (el más rápido)"),
        ...t.pais.disponibles.map((p) => h("option", { value: p.codigo, selected: t.pais.elegido === p.codigo },
          `${bandera(p.codigo)} ${p.nombre}`))]) : null;
    paisSel?.addEventListener("change", async () => {
      try {
        await ctx.api.put("local/tor/pais", { pais: paisSel.value || null }, { timeout: 30000 });
        toast(paisSel.value ? "País de salida guardado: Tor se reinicia para salir por ahí." : "Tor saldrá por el país más rápido.", "ok");
        pintarTor();
      } catch (err) { toastError(err); }
    });
    const bloquePais = paisSel ? h("div", { class: "stack" },
      h("label", { class: "muted" }, "País de salida (cambie su ubicación sin archivos):"), paisSel,
      t.pais_real ? h("p", { class: t.pais_confirmado === false ? "notice notice-warn" : "muted" },
        t.pais_confirmado === false ? `Tor salió por ${t.pais_real}: ese país tiene pocos relevos ahora. Pruebe otro.`
          : `Los sitios lo ven en: ${bandera(t.pais_real)} ${t.pais_real}`) : null) : null;
    const bloqueRed = red ? h("div", { class: "stack" },
      h("label", { class: "muted" }, "Tráfico de GuardianIA a internet (búsqueda web del asistente, filtraciones, firmas):"),
      selector,
      red.bloqueado ? h("p", { class: "notice notice-warn" }, "Modo estricto sin Tor: GuardianIA no sale a internet hasta que actives Tor (tu IP no se expone).")
        : red.saliendo_por_tor ? h("p", { class: "muted" }, "✓ Saliendo por Tor. El DNS lo resuelve Tor (socks5h): sin fugas ni DoH externo.") : null) : null;
    const barra = t.activo && !t.listo ? h("div", { class: "progreso-tor", role: "progressbar",
      "aria-valuenow": t.progreso, "aria-valuemin": 0, "aria-valuemax": 100 },
      h("span", { style: `width:${Math.max(4, t.progreso)}%` })) : null;
    const estado = !t.activo ? "Apagado" : !t.listo ? `Conectando a la red Tor… ${t.progreso}%`
      : t.origen === "tor_browser" ? "Activo (usando el Tor de Tor Browser)" : "Activo";
    mount(torEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" }, h("h2", {}, "Modo cebolla (Tor) — gratis"),
        h("span", { class: `badge ${t.listo ? "badge-ok" : t.activo ? "badge-warn" : ""}` }, estado)),
      h("p", { class: "muted" }, "Su tráfico pasa por 3 relevos cifrados en capas: ninguno sabe a la vez quién es usted "
        + "y qué visita. Más anónimo que una VPN, pero más lento."),
      barra, t.resumen && t.activo && !t.listo ? h("p", { class: "muted" }, t.resumen) : null,
      t.error ? h("p", { class: "notice notice-warn" }, t.error) : null,
      t.is_tor === true ? h("p", { class: "notice" }, `Verificado por el Tor Project: sale por Tor con la IP ${t.ip_salida}. `
        + `Anonimato ${t.anonimato?.nivel || "alto"} (${t.anonimato?.puntaje ?? "—"}/100).`) : null,
      t.is_tor === false && verificar ? h("p", { class: "notice notice-warn" }, t.error || "El Tor Project dice que esta conexión NO sale por Tor.") : null,
      t.listo ? h("p", { class: "muted" }, t.como_usar) : null,
      bloquePais,
      bloqueRed,
      acciones));
    if (t.activo && !t.listo) timerTor = setTimeout(() => { if (document.body.contains(torEl)) pintarTor(); }, 2000);
  }

  // ── nivel de anonimato: IP, IPv6, DNS, kill switch, jurisdicción, operador y registros
  async function pintarAnonimato(repetir) {
    let a;
    try { a = await ctx.api.get(`local/vpn/anonimato?repetir=${repetir ? "true" : "false"}`, { timeout: 60000 }); }
    catch { mount(anonEl); return; }
    const tono = a.nivel === "alto" ? "ok" : a.nivel === "medio" ? "warn" : "danger";
    const marca = { ok: "✓", aviso: "⚠", falla: "✗" };
    const probar = h("button", { class: "btn btn-sm" }, "Probar fugas otra vez");
    probar.addEventListener("click", () => busy(probar, () => pintarAnonimato(true)));
    mount(anonEl, h("section", { class: "panel stack", "aria-live": "polite" },
      h("div", { class: "panel-head" },
        h("h2", {}, `Nivel de anonimato: ${a.nivel} (${a.puntaje}/100)`),
        h("span", { class: `badge badge-${tono === "ok" ? "ok" : tono === "warn" ? "warn" : "danger"}` }, a.nivel)),
      h("ul", { class: "list" }, a.revisiones.map((r) => h("li", {},
        h("div", { class: "list-main" }, h("p", { class: "list-title" }, `${marca[r.estado] || "·"} ${r.texto}`))))),
      a.endurecido?.length ? h("p", { class: "muted" }, "Se cerraron fugas en su archivo: "
        + a.endurecido.map((c) => ({ dns_privado: "DNS privado dentro del túnel", ipv6_por_tunel: "IPv6 por el túnel" }[c] || c)).join(", ") + ".") : null,
      h("details", {}, h("summary", {}, "Lo que una VPN no puede ocultar"),
        h("ul", {}, a.limites.map((l) => h("li", {}, l)))),
      h("div", { class: "row" }, probar)));
  }

  // ── estadísticas en vivo (tablero + botón Verificar)
  async function refrescarStats() {
    try {
      const st = await ctx.api.get("local/vpn/estadisticas");
      if (!st.activo) { pintar(); return false; }
      const dato = (etq, val, sub) => h("div", { class: "monitor-dato" },
        h("span", { class: "monitor-etiqueta" }, etq),
        h("span", { class: "monitor-valor" }, val), sub ? h("span", { class: "monitor-etiqueta" }, sub) : null);
      const verificar = h("button", { class: "btn btn-sm monitor-btn" }, "Verificar salida");
      verificar.addEventListener("click", () => busy(verificar, async () => {
        try {
          const r = await ctx.api.post("local/vpn/verificar");
          toast(r.verificado ? `Verificado: su IP pública es ${r.ip} (${r.pais || "?"})`
            : "El túnel está activo pero su IP no cambió", r.verificado ? "ok" : "error");
          refrescarStats();
        } catch (err) { toastError(err); }
      }));
      mount(statsEl, h("section", { class: "monitor-analisis", dataset: { estado: "analizando",
        tono: st.verificado ? "ok" : "warn" }, "aria-live": "polite" },
        h("div", { class: "monitor-cabeza" },
          h("div", {}, h("h2", {}, "VPN en marcha"),
            h("p", {}, st.verificado ? "Su tráfico va cifrado por el túnel"
              : "Túnel activo; confirme la salida con «Verificar»")),
          h("div", { class: "row" }, verificar)),
        h("div", { class: "monitor-cifra" },
          h("span", { class: "monitor-porcentaje", style: "font-size:clamp(2rem,5vw,3rem)" }, reloj(st.segundos_conectado)),
          h("span", { class: "monitor-sub" }, "conectado")),
        h("div", { class: "monitor-datos" },
          dato("IP de salida", st.ip_salida || "—", st.pais_salida ? `país ${st.pais_salida}` : ""),
          dato("Latencia", st.latencia_ms != null ? `${st.latencia_ms} ms` : "midiendo…"),
          dato("Descargado", bytes(st.recibidos_bytes)),
          dato("Subido", bytes(st.enviados_bytes)),
          dato("Último enlace", st.ultimo_handshake_hace_s != null ? `hace ${st.ultimo_handshake_hace_s} s` : "—",
            st.handshake_ok ? "activo" : "sin datos aún"),
          dato("IP anterior", st.ip_previa || "—")),
        null));
      return true;
    } catch { return false; }
  }
  function vigilarStats(inmediato = false) {
    clearTimeout(timerStats);
    const ciclo = async () => {
      const sigue = await refrescarStats();
      if (!document.body.contains(statsEl)) return;
      timerStats = setTimeout(ciclo, 3000);
      if (!sigue) clearTimeout(timerStats);
    };
    if (inmediato) ciclo(); else timerStats = setTimeout(ciclo, 3000);
  }

  // ── catálogo real + asesor de IA (solo cuando está desconectado)
  const proposito = h("select", { class: "input", "aria-label": "Para qué usará la VPN" });
  async function cargarServidores(e) {
    let datos;
    try { datos = await ctx.api.get(`local/vpn/servidores?proposito=${proposito.value || "general"}`); }
    catch { mount(servidoresEl, origenes(e)); return; }
    if (!proposito.children.length) {
      for (const [v, l] of Object.entries(datos.propositos)) proposito.append(h("option", { value: v }, l));
      proposito.addEventListener("change", () => cargarServidores(ultimoEstado));
    }
    const a = datos.asesor;
    const recomendado = a?.recomendado;
    const tarjetas = datos.servidores.map((s) => tarjetaServidor(s, s.id === recomendado, e));
    mount(servidoresEl, h("section", { class: "panel stack" },
      h("div", { class: "panel-head" },
        h("div", {}, h("h2", {}, "Servidores disponibles"),
          h("p", {}, "Sus ubicaciones de ProtonVPN: una por país.")),
        field("Para qué la usará", proposito)),
      a?.texto ? h("div", { class: "resumen-ia", dataset: { tono: "blue" } },
        h("div", { class: "resumen-ia-icono" }, icon("servidor", { size: 22 })),
        h("div", { class: "stack-sm" }, h("div", { class: "row", style: "gap:.5rem" },
          h("strong", {}, "Recomendación"), a.por_modelo ? badge("IA", "blue") : badge("Automática", "neutral")),
          h("p", {}, a.texto))) : null,
      h("div", { class: "grid grid-2" }, tarjetas),
      h("details", { class: "muted" }, h("summary", {}, "Importar otro proveedor (.conf de Proton, etc.)"),
        origenesImport(e))));
  }

  function tarjetaServidor(s, recomendado, e) {
    const activo = s.activo;
    const acciones = h("div", { class: "row" });
    if (!activo && s.configurado) {
      const conectar = h("button", { class: "btn btn-primary btn-sm" }, "Conectar");
      conectar.addEventListener("click", () => conectarAhora(conectar));
      acciones.append(conectar);
    } else if (s.proveedor === "servidor") {
      const usar = h("button", { class: "btn btn-sm" }, "Usar");
      usar.addEventListener("click", () => busy(usar, async () => {
        try { const r = await ctx.api.post("local/vpn/servidor", { server_id: s.id }); toast(r.mensaje, "ok"); pintar(); }
        catch (err) { toastError(err); }
      }));
      acciones.append(usar);
    }
    return h("div", { class: "servidor-card", dataset: { recomendado: String(recomendado), activo: String(activo) } },
      h("div", { class: "row row-between" },
        h("div", {}, h("h3", {}, s.nombre),
          h("p", { class: "muted" }, s.elige_pais ? `Ubicación: ${s.city}` : s.city)),
        recomendado ? badge("Recomendado", "ok") : s.gratis ? badge("Gratis", "blue") : null),
      h("p", { class: "muted" }, s.nota),
      h("div", { class: "row row-between" },
        h("span", { class: "muted" }, activo ? "Conectado ahora" : s.un_clic ? "Un clic" : "Requiere configurar"),
        acciones));
  }

  function origenesImport(e) {
    const archivo = h("input", { type: "file", accept: ".conf,text/plain", multiple: true, hidden: true });
    const importar = h("button", { class: "btn" }, "Elegir uno o varios .conf");
    importar.addEventListener("click", () => archivo.click());
    archivo.addEventListener("change", async () => {
      const lista = [...archivo.files];
      if (!lista.length) return;
      let ok = 0; const fallos = [];
      for (const f of lista) {                  // uno por uno: cada archivo se valida por separado
        try {
          await ctx.api.post("local/vpn/importar", { texto: await f.text(), nombre: f.name.replace(/\.conf$/i, "") });
          ok++;
        } catch (err) { fallos.push(`${f.name}: ${errorText(err)}`); }
      }
      if (ok) toast(`${ok} ubicación(es) importada(s). Elige una en «Tus ubicaciones».`, "ok");
      if (fallos.length) toast(`No se importaron: ${fallos.join(" · ")}`, "error", { timeout: 12000 });
      archivo.value = "";
      pintar();
    });
    const descargas = h("button", { class: "btn btn-primary" }, "Buscar en mi carpeta Descargas");
    descargas.addEventListener("click", () => busy(descargas, async () => {
      try {
        const r = await ctx.api.post("local/vpn/ubicaciones/importar-descargas", {});
        const n = r.importadas.length;
        toast(n ? `${n} ubicación(es) nueva(s): ${r.importadas.map((i) => i.pais || i.nombre).join(", ")}`
          : r.repetidas ? "No hay archivos nuevos: ya estaban importados." : "No encontré archivos .conf de WireGuard en Descargas.",
          n ? "ok" : "info", { timeout: 9000 });
        if (r.rechazadas.length) toast(`Rechazados por seguridad: ${r.rechazadas.map((x) => x.archivo).join(", ")}`, "error", { timeout: 12000 });
        pintar();
      } catch (err) { toastError(err); }
    }));
    return h("div", { class: "stack-sm", style: "margin-top:.6rem" },
      h("p", { class: "muted" }, "Para cambiar de país: en account.protonvpn.com → Downloads → WireGuard, descargue un "
        + "archivo por cada país gratuito (quedan en Descargas). Luego presione «Buscar en mi carpeta Descargas»: "
        + "GuardianIA los importa todos y detecta el país. Se rechazan los .conf que ejecutan comandos."),
      h("div", { class: "row" }, descargas, importar), archivo);
  }

  function origenes(e) { return origenesImport(e); }

  await pintar();
  return raiz;
}
