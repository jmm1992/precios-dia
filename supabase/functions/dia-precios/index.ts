// dia-precios: intermediario entre la app y la web de Día (diaonline.supermercadosdia.com.ar).
// La web de Día no deja que otra página le pida datos desde el navegador (no manda CORS),
// así que la app le pregunta a esta función y la función le pregunta a Día.
// De Día sólo LEE datos públicos (los mismos que ve cualquiera en la web, sin cuenta).
// En la base guarda las cuentas de la app, la lista de cada cuenta y la historia de precios (tablas pd_).
//
//   ?do=tiendas&cp=1766                       tiendas Día con retiro cerca de un código postal
//   ?do=rubros                                los rubros y sub-rubros de la tienda
//   POST {do:'listar', tienda, modo, cat, q, orden}
//        modo: 'ofertas' (toda la tienda o un rubro) · 'rubro' · 'buscar'
//        orden: 'descuento' · 'unidad' (precio por kg/litro) · 'precio' · 'caro' · 'nombre'
//        con modo 'rubro' y q, busca ese texto dentro del rubro
//   POST {do:'precios', tienda, ids:[...]}    productos puntuales
//   POST {do:'crearCuenta' | 'entrar', usuario, clave}  cuentas propias → {pase, usuario}
//   POST {do:'seguir', tienda, pase, id, nombre, imagen, si}   agregar/sacar de "Mi lista" de esa cuenta
//   POST {do:'seguidos', tienda, pase}            Mi lista con el precio de hoy y la historia guardada día por día
//   ?do=capturar                              foto diaria de lo que sigue cualquier cuenta (la llama pg_cron a las 9 de Argentina)
//
// Todo producto se confirma en la tienda elegida con un carrito de prueba (sin comprar ni cuenta):
// con 1 unidad sale el precio real y si hay stock para retirar; con 2 unidades, si Día aplica
// una promo de "llevando 2". Las etiquetas de promo del catálogo no se usan: muchas están vencidas.

const BASE = "https://diaonline.supermercadosdia.com.ar";
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const HDR = { "User-Agent": "Mozilla/5.0 (precios-dia)", "Accept": "application/json" };
const CLUSTER_OFERTAS = 567;   // "Todas las Ofertas" de Día

const json = (d: unknown, status = 200) =>
  new Response(JSON.stringify(d), { status, headers: { ...CORS, "Content-Type": "application/json" } });

async function pedir(url: string, init: RequestInit = {}) {
  const r = await fetch(url, { ...init, headers: { ...HDR, ...(init.headers || {}) } });
  if (!r.ok && r.status !== 206) throw new Error(`Día contestó ${r.status}`);
  return r.json();
}

/* ---------- Tiendas ---------- */
async function tiendas(cp: string) {
  const j = await pedir(`${BASE}/api/checkout/pub/pickup-points?postalCode=${encodeURIComponent(cp)}&countryCode=ARG`);
  const vistos = new Set<string>(), out = [];
  for (const x of (j.items || [])) {
    const p = x.pickupPoint || x;
    const seller = String(p.id || "").split("_")[0];
    if (!seller || vistos.has(seller) || /DRIVE$/i.test(p.id)) continue;
    vistos.add(seller);
    const a = p.address || {};
    out.push({
      seller,
      nombre: String(p.friendlyName || "").replace(/^Retiro en tienda\s*/i, "").trim(),
      direccion: [a.street, a.number].filter(Boolean).join(" ").trim(),
      localidad: (a.neighborhood || a.city || "").trim(),
      geo: a.geoCoordinates,
      distanciaKm: Math.round((x.distance || 0) * 100) / 100,
    });
  }
  return out;
}

/* ---------- Rubros ---------- */
let rubrosCache: { t: number; d: unknown } | null = null;
async function rubros() {
  if (rubrosCache && Date.now() - rubrosCache.t < 6 * 3600e3) return rubrosCache.d;
  const j = await pedir(`${BASE}/api/catalog_system/pub/category/tree/3`);
  const d = (j || []).map((c: any) => ({
    id: c.id, nombre: c.name, cat: `/${c.id}/`,
    hijos: (c.children || []).map((s: any) => ({
      id: s.id, nombre: s.name, cat: `/${c.id}/${s.id}/`,
      hijos: (s.children || []).map((n: any) => ({ id: n.id, nombre: n.name, cat: `/${c.id}/${s.id}/${n.id}/` })),
    })),
  }));
  rubrosCache = { t: Date.now(), d };
  return d;
}

/* ---------- Catálogo ---------- */
function ficha(p: any) {
  const it = p.items?.[0] || {}, o = it.sellers?.[0]?.commertialOffer || {};
  const pxu = Number((p.PrecioPorUnd || [])[0]);
  const um = String((p.UnidaddeMedida || [])[0] || "");
  // Día calcula su precio por unidad sobre el precio de LISTA: se guarda la proporción y se aplica al precio real.
  const factor = pxu > 0 && o.ListPrice > 0 ? pxu / o.ListPrice : null;
  const unidad = /kg/i.test(um) ? "kg" : /(^|\s)(lt|l|litro)/i.test(um) ? "litro" : null;
  const partes = String((p.categories || [])[0] || "").split("/").filter(Boolean);
  // Promo vigente con su nombre ("2do al 50%", "3x2"): viene en PromotionTeasers, que es lo que muestra la web de Día.
  // (Los nombres de los clusters NO sirven: muchos son de campañas vencidas.)
  // La cantidad que pide la promo sale del nombre ("3x2" → 3, "2x$3000" → 2, "2do al 50%" → 2) o de su mínimo.
  const cantDe = (n: string, min: number) => {
    const m = /^\s*(\d+)\s*x/i.exec(n) || /^\s*(\d+)\s*(do|da|ro|ra|to|ta)\b/i.exec(n);
    return Math.max(min || 1, m ? Number(m[1]) : 1);
  };
  const promo = (o.PromotionTeasers || []).map((x: any) => ({ nombre: x.Name, minimo: cantDe(String(x.Name || ""), x.Conditions?.MinimumQuantity) }))
    .find((x: any) => x.nombre) || null;
  return {
    id: String(it.itemId), nombre: p.productName, marca: p.brand, link: p.link, ean: String(it.ean || ""),
    imagen: it.images?.[0]?.imageUrl || "",
    rubro: partes[0] || "", subrubro: partes[1] || "", subsub: partes[2] || "",
    precioCat: o.Price ?? null, listaCat: o.ListPrice ?? null, stockOnline: o.AvailableQuantity ?? 0,
    factor: unidad ? factor : null, unidad, promoNombre: promo?.nombre || null, promoMinimo: promo?.minimo || null,
  };
}

// Memoria de la función: lo que ya se consultó no se vuelve a pedir enseguida (la web de Día tarda ~2 s por pedido)
const memo = new Map<string, { t: number; d: any }>();
async function conMemo<T>(clave: string, ms: number, fn: () => Promise<T>): Promise<T> {
  const m = memo.get(clave);
  if (m && Date.now() - m.t < ms) return m.d;
  const d = await fn();
  memo.set(clave, { t: Date.now(), d });
  if (memo.size > 3000) for (const k of [...memo.keys()].slice(0, 1000)) memo.delete(k);
  return d;
}

async function catalogo(params: string, paginas = 2) {
  const reqs = [];
  for (let k = 0; k < paginas; k++) {
    const url = `${BASE}/api/catalog_system/pub/products/search?${params}&_from=${k * 50}&_to=${k * 50 + 49}`;
    reqs.push(conMemo("cat|" + url, 5 * 60e3, () => pedir(url)).catch(() => []));
  }
  const vistos = new Set<string>(), out = [];
  for (const p of (await Promise.all(reqs)).flat()) {
    const f = ficha(p);
    if (f.id && !vistos.has(f.id)) { vistos.add(f.id); out.push(f); }
  }
  return out;
}

async function simular(ids: string[], t: { seller: string; geo: number[] }, cantidad: number) {
  const out: Record<string, any> = {};
  for (let i = 0; i < ids.length; i += 100) {
    const body = {
      items: ids.slice(i, i + 100).map((id) => ({ id, quantity: cantidad, seller: t.seller })),
      country: "ARG", geoCoordinates: t.geo,
    };
    const j = await pedir(`${BASE}/api/checkout/pub/orderForms/simulation?sc=1`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    // Con una promo tipo "2do al 50%" Día parte el producto en VARIOS renglones (uno con descuento y otro sin):
    // hay que sumarlos. Quedarse con uno solo perdía la promo.
    for (const it of j.items || []) {
      const id = String(it.id), q = it.quantity || 0;
      const total = (it.priceDefinition?.total ?? it.sellingPrice * q) / 100;
      const o = out[id] || (out[id] = { total: 0, cant: 0, lista: it.listPrice / 100, disp: false });
      o.total += total; o.cant += q; o.disp = o.disp || it.availability === "available";
    }
  }
  for (const o of Object.values(out)) o.unit = o.cant ? o.total / o.cant : null;
  return out;
}

const r2 = (x: number) => Math.round(x * 100) / 100;

// Otros supermercados (mismo sistema que Día): se buscan los mismos productos por código de barras.
// Sus precios NO se devuelven a la app (decisión de él): sólo sirven para marcar un precio de Día
// que está muy por debajo de lo que vale en otros lados (ej.: una muzzarella a $1).
const OTROS = ["https://www.carrefour.com.ar", "https://www.masonline.com.ar"];
const otrosCache = new Map<string, { t: number; precios: number[] }>();   // por código de barras, 6 horas
async function preciosOtros(eans: string[]) {
  const out: Record<string, number[]> = {};
  const faltan: string[] = [];
  for (const e of eans) {
    const c = otrosCache.get(e);
    if (c && Date.now() - c.t < 6 * 3600e3) { if (c.precios.length) out[e] = c.precios; }
    else faltan.push(e);
  }
  const lotes = [];
  for (let i = 0; i < faltan.length; i += 40) lotes.push(faltan.slice(i, i + 40));
  const contestaron = new Set<string>();
  await Promise.all(OTROS.flatMap((s) => lotes.map(async (l) => {
    try {
      const fq = l.map((e) => `fq=alternateIds_Ean:${encodeURIComponent(e)}`).join("&");
      const j = await pedir(`${s}/api/catalog_system/pub/products/search?${fq}&_from=0&_to=49`);
      l.forEach((e) => contestaron.add(s + "|" + e));
      for (const p of j || []) for (const it of p.items || []) {
        const o = it.sellers?.[0]?.commertialOffer;
        if (it.ean && o && o.Price > 0 && o.AvailableQuantity > 0) (out[it.ean] ||= []).push(o.Price);
      }
    } catch { /* si un supermercado no contesta, se compara con el otro */ }
  })));
  // se recuerda sólo lo que contestaron los dos (si uno falló, se vuelve a preguntar la próxima)
  for (const e of faltan) if (OTROS.every((s) => contestaron.has(s + "|" + e))) otrosCache.set(e, { t: Date.now(), precios: out[e] || [] });
  return out;
}

async function enTienda(fichas: any[], t: any) {
  const ids = fichas.map((f) => f.id);
  if (!ids.length) return [];
  const eans = [...new Set(fichas.map((f) => f.ean).filter((e) => /^\d{8,14}$/.test(e)))];
  // La prueba con varias unidades sólo para los que tienen una promo de "llevando N" (con la cantidad mínima de la promo)
  const conPromo = fichas.filter((f) => f.promoMinimo && f.promoMinimo >= 2);
  const porMinimo = new Map<number, string[]>();
  for (const f of conPromo) (porMinimo.get(f.promoMinimo) || porMinimo.set(f.promoMinimo, []).get(f.promoMinimo)!).push(f.id);
  const [uno, varios, otros] = await Promise.all([
    simular(ids, t, 1),
    Promise.all([...porMinimo.entries()].map(([n, l]) => simular(l, t, n).catch(() => ({})))).then((rs) => Object.assign({}, ...rs)),
    preciosOtros(eans).catch(() => ({})),
  ]);
  return fichas.map((f) => {
    const a = uno[f.id], b = (varios as any)[f.id];
    const ref = ((otros as any)[f.ean] || []) as number[];
    const precio = a?.unit ?? f.precioCat;
    const lista = Math.max(a?.lista || 0, f.listaCat || 0) || precio;
    const conPromoUnit = b && b.unit < precio * 0.985 ? r2(b.unit) : null;   // precio por unidad llevando la cantidad de la promo
    const minOtros = ref.length ? Math.min(...ref) : null;
    // Precio muy bajo, sólo con pruebas firmes:
    //  'otros': cuesta la mitad o menos que en el más barato de Carrefour / ChangoMás (mismo código de barras);
    //  'lista': cuesta 10% o menos de su propio precio de lista.
    const motivoBajo = minOtros != null && precio <= minOtros * 0.5 ? "otros" : lista > 0 && precio <= lista * 0.1 ? "lista" : null;
    const { factor, promoMinimo, ...resto } = f;
    return {
      ...resto, precio, lista, muyBajo: !!motivoBajo, motivoBajo, comparadoOtros: ref.length > 0,
      promoCant: conPromoUnit ? promoMinimo : null,
      enTienda: !!a, disponible: !!(a && a.disp),
      descuento: lista > precio ? r2((1 - precio / lista) * 100) : 0,
      llevando2: conPromoUnit, descuento2: conPromoUnit ? r2((1 - conPromoUnit / precio) * 100) : 0,
      porUnidad: f.factor ? r2(precio * f.factor) : null,
      porUnidad2: f.factor && conPromoUnit ? r2(conPromoUnit * f.factor) : null,
    };
  });
}

// Marcas que se pueden afirmar con los datos del propio listado
function marcar(lista: any[]) {
  const grupos = new Map<string, number[]>();
  for (const p of lista) if (p.porUnidad) {
    const k = p.subsub + "|" + p.unidad;
    (grupos.get(k) || grupos.set(k, []).get(k)!).push(p.porUnidad);
  }
  const med = new Map<string, number>();
  for (const [k, v] of grupos) if (v.length >= 5) { v.sort((a, b) => a - b); med.set(k, v[Math.floor(v.length / 2)]); }
  for (const p of lista) {
    const m = p.porUnidad ? med.get(p.subsub + "|" + p.unidad) : undefined;
    p.medianaGrupo = m ?? null;
    p.vsGrupo = m ? r2((p.porUnidad / m - 1) * 100) : null;   // % contra el precio por kg/litro típico de su grupo
  }
  return lista;
}

function ordenar(lista: any[], orden: string) {
  const mejor = (p: any) => Math.max(p.descuento, p.llevando2 ? (1 - p.llevando2 / p.lista) * 100 : 0);
  if (orden === "unidad") return lista.sort((a, b) => (a.porUnidad2 ?? a.porUnidad ?? 1e12) - (b.porUnidad2 ?? b.porUnidad ?? 1e12));
  if (orden === "precio") return lista.sort((a, b) => (a.llevando2 ?? a.precio) - (b.llevando2 ?? b.precio));
  if (orden === "caro") return lista.sort((a, b) => b.precio - a.precio);
  if (orden === "nombre") return lista.sort((a, b) => String(a.nombre).localeCompare(String(b.nombre), "es"));
  return lista.sort((a, b) => (Number(b.muyBajo) - Number(a.muyBajo)) || (mejor(b) - mejor(a)));
}
const ORDEN_DIA: Record<string, string> = {
  descuento: "OrderByBestDiscountDESC", unidad: "OrderByPriceASC", precio: "OrderByPriceASC",
  caro: "OrderByPriceDESC", nombre: "OrderByNameASC",
};

async function listar(c: any) {
  const t = c.tienda;
  if (!t?.seller || !Array.isArray(t.geo)) throw new Error("Falta la tienda");
  const cat = typeof c.cat === "string" && /^\/[\d/]+\/$/.test(c.cat) ? c.cat : "";
  const q = String(c.q || "").trim();
  const orden = ORDEN_DIA[c.orden] ? c.orden : "descuento";
  let params = "";
  if (c.modo === "buscar") {
    if (q.length < 2) throw new Error("Escribí al menos 2 letras");
    params = `ft=${encodeURIComponent(q)}`;
  } else if (c.modo === "ofertas") {
    params = (cat ? `fq=C:${cat}` : `fq=productClusterIds:${CLUSTER_OFERTAS}`) + "&O=OrderByBestDiscountDESC";
  } else {
    if (!cat) throw new Error("Falta el rubro");
    params = `fq=C:${cat}` + (q.length >= 2 ? `&ft=${encodeURIComponent(q)}` : "") + `&O=${ORDEN_DIA[orden]}`;
  }
  // Para comparar por kilo hay que mirar más productos: los envases grandes son caros por unidad pero baratos por kilo
  let fichas = await catalogo(params, c.modo === "rubro" && orden === "unidad" ? 4 : 2);
  if (c.modo === "ofertas") {
    // Las promos "2do al 50%" / "3x2" no bajan el precio de lista, así que no salen ordenando por descuento:
    // se suman los más vendidos en oferta, que es donde están.
    const vendidos = await catalogo(params.replace("OrderByBestDiscountDESC", "OrderByTopSaleDESC"), 1);
    const ya = new Set(fichas.map((f) => f.id));
    fichas = fichas.concat(vendidos.filter((f) => f.promoNombre && !ya.has(f.id)));
  }
  const lista = marcar(await enTienda(fichas, t));
  return { hora: new Date().toISOString(), productos: ordenar(lista, orden) };
}

async function precios(c: any) {
  const ids = [...new Set((c.ids || []).map(String))].slice(0, 100) as string[];
  if (!ids.length) return { hora: new Date().toISOString(), productos: [] };
  const fichas = [];
  for (let i = 0; i < ids.length; i += 40)
    fichas.push(...await catalogo(ids.slice(i, i + 40).map((id) => `fq=skuId:${encodeURIComponent(id)}`).join("&"), 1));
  return { hora: new Date().toISOString(), productos: await enTienda(fichas, c.tienda) };
}

/* ---------- Mi lista e historia de precios (tablas pd_ del proyecto, sólo con la clave de servicio) ---------- */
const SB_URL = Deno.env.get("SUPABASE_URL") || "";
const SB_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
async function db(ruta: string, init: RequestInit = {}) {
  const r = await fetch(`${SB_URL}/rest/v1/${ruta}`, {
    ...init,
    headers: { apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}`, "Content-Type": "application/json", ...(init.headers || {}) },
  });
  if (!r.ok) throw new Error(`base de datos ${r.status}: ${(await r.text()).slice(0, 200)}`);
  const txt = await r.text();
  return txt ? JSON.parse(txt) : null;
}
const hoyAR = () => new Date(Date.now() - 3 * 3600e3).toISOString().slice(0, 10);   // fecha de Argentina (UTC-3)
const tiendaOk = (t: any) => t && typeof t.seller === "string" && /^[\w-]{3,40}$/.test(t.seller) && Array.isArray(t.geo) && t.geo.length === 2;

// Guarda la foto del día de esos productos (si ya había una de hoy, la actualiza)
async function guardarFoto(seller: string, productos: any[]) {
  const filas = productos.filter((p) => p.enTienda).map((p) => ({
    fecha: hoyAR(), seller, item_id: p.id, precio: p.precio, lista: p.lista,
    promo_unit: p.llevando2, promo_nombre: p.llevando2 ? p.promoNombre : null, disponible: p.disponible,
    capturado: new Date().toISOString(),
  }));
  if (filas.length) await db("pd_historia?on_conflict=fecha,seller,item_id", {
    method: "POST", headers: { Prefer: "resolution=merge-duplicates" }, body: JSON.stringify(filas),
  });
}

/* ---------- Cuentas propias (usuario y contraseña, sin correo) ---------- */
// La contraseña se guarda cifrada (PBKDF2); al entrar se da un pase firmado con la clave de servicio que vale un año.
class Aviso extends Error {}   // errores para mostrar tal cual (no son de Día)
class SinSesion extends Aviso {}   // el pase falta, no vale o venció: la app vuelve al ingreso
const enc = new TextEncoder();
const b64u = (b: ArrayBuffer | Uint8Array) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const desdeB64u = (t: string) => new TextDecoder().decode(Uint8Array.from(atob(t.replace(/-/g, "+").replace(/_/g, "/")), (ch) => ch.charCodeAt(0)));
async function hashClave(clave: string, sal: string) {
  const k = await crypto.subtle.importKey("raw", enc.encode(clave), "PBKDF2", false, ["deriveBits"]);
  return b64u(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: enc.encode(sal), iterations: 100000 }, k, 256));
}
let llaveFirma: CryptoKey | null = null;
async function firmar(txt: string) {
  llaveFirma ||= await crypto.subtle.importKey("raw", enc.encode("precios-dia:" + SB_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return b64u(await crypto.subtle.sign("HMAC", llaveFirma, enc.encode(txt)));
}
async function pase(id: string, usuario: string) {
  const cuerpo = b64u(enc.encode(JSON.stringify({ u: id, n: usuario, e: Date.now() + 365 * 864e5 })));
  return `${cuerpo}.${await firmar(cuerpo)}`;
}
async function cuentaDe(c: any): Promise<{ id: string; usuario: string }> {
  const [cuerpo, f] = String(c.pase || "").split(".");
  if (!cuerpo || !f || f !== await firmar(cuerpo)) throw new SinSesion("Entrá con tu cuenta para usar Mi lista");
  const d = JSON.parse(desdeB64u(cuerpo));
  if (!d.u || !(d.e > Date.now())) throw new SinSesion("Tu sesión venció: entrá de nuevo");
  return { id: d.u, usuario: d.n };
}
const usuarioOk = (u: string) => /^[a-z0-9._-]{3,30}$/.test(u);

async function crearCuenta(c: any) {
  const usuario = String(c.usuario || "").trim().toLowerCase(), clave = String(c.clave || "");
  if (!usuarioOk(usuario)) throw new Aviso("El usuario va de 3 a 30 letras o números, sin espacios");
  if (clave.length < 6) throw new Aviso("La contraseña tiene que tener al menos 6 caracteres");
  if ((await db(`pd_cuentas?select=id&usuario=eq.${encodeURIComponent(usuario)}`) || []).length) throw new Aviso("Ese usuario ya existe: elegí otro o entrá con él");
  const sal = b64u(crypto.getRandomValues(new Uint8Array(16)));
  const [cta] = await db("pd_cuentas", { method: "POST", headers: { Prefer: "return=representation" },
    body: JSON.stringify([{ usuario, sal, hash: await hashClave(clave, sal), ultimo_ingreso: new Date().toISOString() }]) });
  // La lista de antes de que hubiera cuentas (una sola, compartida) pasa a la primera cuenta que se crea
  let migrados = 0;
  const viejos = await db("pd_seguidos?select=seller,item_id,nombre,imagen,geo,agregado") || [];
  if (viejos.length) {
    await db("pd_lista?on_conflict=cuenta,seller,item_id", { method: "POST", headers: { Prefer: "resolution=merge-duplicates" },
      body: JSON.stringify(viejos.map((v: any) => ({ ...v, cuenta: cta.id }))) });
    await db("pd_seguidos?seller=neq.__nada__", { method: "DELETE" });
    migrados = viejos.length;
  }
  return { pase: await pase(cta.id, usuario), usuario, migrados };
}
async function entrar(c: any) {
  const usuario = String(c.usuario || "").trim().toLowerCase(), clave = String(c.clave || "");
  const [cta] = usuarioOk(usuario) ? (await db(`pd_cuentas?select=id,sal,hash&usuario=eq.${encodeURIComponent(usuario)}`) || []) : [];
  if (!cta || await hashClave(clave, cta.sal) !== cta.hash) throw new Aviso("Usuario o contraseña incorrectos");
  await db(`pd_cuentas?id=eq.${cta.id}`, { method: "PATCH", body: JSON.stringify({ ultimo_ingreso: new Date().toISOString() }) });
  return { pase: await pase(cta.id, usuario), usuario };
}

async function seguir(c: any) {
  const yo = await cuentaDe(c);
  const t = c.tienda, id = String(c.id || "");
  if (!tiendaOk(t) || !/^\d{1,12}$/.test(id)) throw new Error("Faltan datos");
  if (c.si === false) {
    await db(`pd_lista?cuenta=eq.${yo.id}&seller=eq.${t.seller}&item_id=eq.${id}`, { method: "DELETE" });   // la historia se conserva
    return { ok: true };
  }
  const n = await db(`pd_lista?select=item_id&cuenta=eq.${yo.id}&seller=eq.${t.seller}`);
  if ((n || []).length >= 100) throw new Aviso("Tu lista ya tiene 100 productos");
  await db("pd_lista?on_conflict=cuenta,seller,item_id", {
    method: "POST", headers: { Prefer: "resolution=merge-duplicates" },
    body: JSON.stringify([{ cuenta: yo.id, seller: t.seller, item_id: id, nombre: String(c.nombre || "").slice(0, 200), imagen: String(c.imagen || "").slice(0, 500), geo: t.geo }]),
  });
  const r = await precios({ tienda: t, ids: [id] });
  await guardarFoto(t.seller, r.productos);
  return { ok: true };
}

// La lista de la cuenta con el precio de hoy (en vivo) y todo lo guardado día por día (la historia es de la tienda, compartida)
async function seguidos(c: any) {
  const yo = await cuentaDe(c);
  const t = c.tienda;
  if (!tiendaOk(t)) throw new Error("Falta la tienda");
  const filas = await db(`pd_lista?select=item_id,nombre,imagen,agregado&cuenta=eq.${yo.id}&seller=eq.${t.seller}&order=agregado.asc`) || [];
  const ids = filas.map((f: any) => f.item_id);
  if (!ids.length) return { hora: new Date().toISOString(), usuario: yo.usuario, seguidos: [], productos: [], historia: {} };
  const vivo = await precios({ tienda: t, ids });
  await guardarFoto(t.seller, vivo.productos).catch(() => {});
  const desde = new Date(Date.now() - 400 * 864e5).toISOString().slice(0, 10);
  const h = await db(`pd_historia?select=fecha,item_id,precio,lista,promo_unit,promo_nombre,disponible&seller=eq.${t.seller}&fecha=gte.${desde}&item_id=in.(${ids.join(",")})&order=fecha.asc`) || [];
  const historia: Record<string, any[]> = {};
  for (const r of h) (historia[r.item_id] ||= []).push({ fecha: r.fecha, precio: Number(r.precio), lista: Number(r.lista), promo: r.promo_unit != null ? Number(r.promo_unit) : null, promoNombre: r.promo_nombre, disponible: r.disponible });
  return { hora: vivo.hora, usuario: yo.usuario, seguidos: filas, productos: vivo.productos, historia };
}

// La tarea automática de todos los días: foto de precios de lo que sigue cualquier cuenta, tienda por tienda
async function capturar() {
  const filas = await db("pd_lista?select=seller,item_id,geo") || [];
  const porTienda = new Map<string, { geo: number[]; ids: Set<string> }>();
  for (const f of filas) {
    const x = porTienda.get(f.seller) || porTienda.set(f.seller, { geo: f.geo, ids: new Set() }).get(f.seller)!;
    x.ids.add(f.item_id); if (!x.geo && f.geo) x.geo = f.geo;
  }
  const res: Record<string, number> = {};
  for (const [seller, x] of porTienda) {
    if (!Array.isArray(x.geo)) continue;
    const r = await precios({ tienda: { seller, geo: x.geo }, ids: [...x.ids] });
    await guardarFoto(seller, r.productos);
    res[seller] = r.productos.length;
  }
  return { fecha: hoyAR(), guardados: res };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  try {
    const url = new URL(req.url);
    const c = req.method === "POST" ? await req.json().catch(() => ({})) : {};
    const accion = url.searchParams.get("do") || c.do;
    if (accion === "tiendas") return json({ tiendas: await tiendas(url.searchParams.get("cp") || c.cp || "1766") });
    if (accion === "rubros") return json({ rubros: await rubros() });
    if (accion === "listar") return json(await listar(c));
    if (accion === "precios") return json(await precios(c));
    if (accion === "seguir") return json(await seguir(c));
    if (accion === "seguidos") return json(await seguidos(c));
    if (accion === "capturar") return json(await capturar());
    if (accion === "crearCuenta") return json(await crearCuenta(c));
    if (accion === "entrar") return json(await entrar(c));
    return json({ error: "Acción desconocida" }, 400);
  } catch (e) {
    if (e instanceof Aviso) return json({ error: e.message, sesion: e instanceof SinSesion }, 400);
    return json({ error: "No se pudo consultar a Día: " + String((e as Error).message || e) }, 502);
  }
});
