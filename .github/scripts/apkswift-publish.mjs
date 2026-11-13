// Kirim versi baru Kas Kelas ke situs ApkSwift (Firestore proyek apkswift-4ea9e) secara otomatis.
// Dipanggil workflow setelah GitHub Release terbit. Situs ApkSwift membangun ulang dirinya tiap 15 menit dari data ini.
// Env: APKSWIFT_USER, APKSWIFT_PASS (akun developer ApkSwift), APK_FILE, APK_URL, VERSION, NOTES
import { statSync, readFileSync, existsSync } from "node:fs";

const API_KEY = "AIzaSyAkIQW8CF8CbtGU04XWYCatJDCjvn6oZDI";   // kunci web publik ApkSwift (sama dengan yang ada di halamannya)
const PROJECT = "apkswift-4ea9e", DEV_EMAIL = "dev@toko-apk.local", REFERER = "https://apkswift.web.app/";
const PKG = "com.kaskelas.app", ID = PKG;
const { APKSWIFT_USER, APKSWIFT_PASS, APK_FILE, APK_URL, VERSION, NOTES } = process.env;

if (!APKSWIFT_USER || !APKSWIFT_PASS) { console.log("Secret APKSWIFT_USER / APKSWIFT_PASS belum diisi, kirim ke ApkSwift dilewati."); process.exit(0); }

const fmtSize = (b) => (b > 1048576 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB");
const J = (r) => r.json().catch(() => ({}));

// 1) masuk sebagai developer (format sandi sama seperti halaman developer: "pengguna|sandi")
let r = await fetch("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + API_KEY, {
  method: "POST", headers: { "content-type": "application/json", referer: REFERER },
  body: JSON.stringify({ email: DEV_EMAIL, password: APKSWIFT_USER.trim() + "|" + APKSWIFT_PASS, returnSecureToken: true }),
});
let j = await J(r);
if (!r.ok) { console.error("Login ApkSwift gagal:", (j.error && j.error.message) || r.status); process.exit(1); }
const H = { authorization: "Bearer " + j.idToken, "content-type": "application/json", referer: REFERER };

// 2) baca dokumen aplikasi (kalau sudah ada)
const base = "https://firestore.googleapis.com/v1/projects/" + PROJECT + "/databases/(default)/documents/apps/" + encodeURIComponent(ID);
const dec = (v) => v == null ? null : "stringValue" in v ? v.stringValue : "integerValue" in v ? Number(v.integerValue) : "doubleValue" in v ? v.doubleValue
  : "booleanValue" in v ? v.booleanValue : "arrayValue" in v ? (v.arrayValue.values || []).map(dec) : "mapValue" in v ? Object.fromEntries(Object.entries(v.mapValue.fields || {}).map(([k, x]) => [k, dec(x)])) : null;
const enc = (v) => typeof v === "string" ? { stringValue: v } : typeof v === "number" ? { integerValue: String(Math.round(v)) } : Array.isArray(v) ? { arrayValue: { values: v.map(enc) } }
  : { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, enc(x)])) } };

r = await fetch(base, { headers: H });
let cur = null;
if (r.ok) { const d = await r.json(); cur = Object.fromEntries(Object.entries(d.fields || {}).map(([k, v]) => [k, dec(v)])); }
else if (r.status !== 404) { console.error("Gagal membaca ApkSwift:", r.status); process.exit(1); }

// 3) tambah / ganti versi ini di urutan paling atas
const size = fmtSize(statSync(APK_FILE).size);
const date = new Date().toLocaleDateString("id-ID", { day: "numeric", month: "short", year: "numeric", timeZone: "Asia/Makassar" });
// catatan versi: ambil bagian "## <versi>" dari CHANGELOG.md; bila tidak ada, pakai NOTES (judul commit)
let log = (NOTES || "").trim();
if (existsSync("CHANGELOG.md")) {
  const m = readFileSync("CHANGELOG.md", "utf8").split(/^## +/m).find((b) => b.split("\n")[0].trim() === VERSION);
  if (m) log = m.split("\n").slice(1).join("\n").trim() || log;
}
const entry = { v: VERSION, url: APK_URL, size, log: log.slice(0, 1500), date };
const doc = {
  name: (cur && cur.name) || "Kas Kelas", pkg: PKG,
  cat: (cur && cur.cat) || "Pendidikan",
  desc: (cur && cur.desc) || "Aplikasi kas kelas: catat iuran, pengeluaran, dan tunggakan, lengkap dengan laporan dan backup otomatis.",
  icon: (cur && cur.icon) || "",
  versions: [entry].concat(((cur && cur.versions) || []).filter((x) => x.v !== VERSION)).slice(0, 200),
  updated: Date.now(),
};
const fields = Object.fromEntries(Object.entries(doc).map(([k, v]) => [k, enc(v)]));
r = await fetch(base, { method: "PATCH", headers: H, body: JSON.stringify({ fields }) });
if (!r.ok) { console.error("Gagal menulis ke ApkSwift:", r.status, (await r.text()).slice(0, 300)); process.exit(1); }
console.log("ApkSwift diperbarui: Kas Kelas " + VERSION + " (" + size + "). Situs tampil dalam maks. 15 menit.");
