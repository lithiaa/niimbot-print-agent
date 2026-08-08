# 🖨️ Niimbot Print Agent — Panduan Lengkap

Aplikasi Android **print agent** untuk printer label **Niimbot B1 Pro**. Terima print job dari cloud POS via HTTP, generate label, kirim ke printer via Bluetooth LE.

---

## 📋 Daftar Isi

1. [Gambaran Arsitektur](#-1-gambaran-arsitektur)
2. [Persiapan Awal](#-2-persiapan-awal)
3. [Build APK](#-3-build-apk)
4. [Install ke Tablet](#-4-install-ke-tablet)
5. [Konfigurasi Pertama](#-5-konfigurasi-pertama)
6. [Integrasi Cloud POS](#-6-integrasi-cloud-pos)
7. [Test End-to-End](#-7-test-end-to-end)
8. [Troubleshooting](#-8-troubleshooting)
9. [Struktur Project](#-9-struktur-project)

---

## 🏗 1. Gambaran Arsitektur

```
┌─────────────┐     HTTP POST /print     ┌──────────────────┐
│ Cloud POS   │ ────────────────────────→ │  Android Tablet  │
│ (FastAPI)   │                          │  Niimbot Print    │
│             │ ←──────────────────────── │  Agent App       │
└─────────────┘     {"success":true}      └────────┬─────────┘
                                                    │ BLE
                                                    ▼
                                          ┌──────────────────┐
                                          │  Niimbot B1 Pro  │
                                          │  (Label Printer) │
                                          └──────────────────┘
```

**Alur print label:**
1. Cloud POS generate data barang (nama, harga, SKU, stok)
2. Kirim ke app via `POST /print` (JSON atau gambar)
3. App generate bitmap label (584×354px, Code128 barcode)
4. App kirim ke printer via Bluetooth LE
5. Printer cetak label

---

## 🛠 2. Persiapan Awal

### Yang dibutuhkan:

| Item | Keterangan |
|---|---|
| **Laptop/PC** | Untuk build APK — Windows/Mac/Linux |
| **Android Studio** | [Download di sini](https://developer.android.com/studio) (Ladybug atau lebih baru) |
| **Tablet/HP Android** | Android 7.0+ (API 24), punya Bluetooth |
| **Niimbot B1 Pro** | Printer label, sudah terisi kertas & baterai |
| **Kabel USB** | Untuk install APK ke tablet (atau pakai WiFi ADB) |
| **WiFi** | Tablet dan laptop dalam 1 jaringan (untuk tes LAN) |

### Install Android Studio:
1. Download dari https://developer.android.com/studio
2. Install (Next-next sampai selesai)
3. Saat pertama buka: **SDK Manager → Install SDK Platform 34 + Build-Tools 34.0.0** (centang otomatis)

---

## 📦 3. Build APK

### Step 1: Copy project ke laptop

**Dari server (kalau kamu di server ini):**
```bash
cd /home/ubuntu
zip -r niimbot-print-agent.zip niimbot-print-agent/
# Download zip-nya ke laptop
```

**Dari GitHub (kalau sudah di-push):**
```bash
git clone git@github.com:lithiaa/niimbot-print-agent.git
```

### Step 2: Buka di Android Studio

1. **File → Open** → pilih folder `niimbot-print-agent`
2. Tunggu **Gradle Sync** selesai (download dependencies, 2-5 menit pertama kali)
3. Kalau diminta update plugin/dependency → **Update**

### Step 3: Build APK

**Cara 1 — Android Studio (mudah):**
1. Menu: **Build → Build Bundle(s)/APK(s) → Build APK(s)**
2. Tunggu sampai muncul notifikasi "APK(s) generated successfully"
3. Klik **"locate"** → folder `app/build/outputs/apk/debug/`

**Cara 2 — Terminal (kalau mau):**
```bash
cd niimbot-print-agent
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# APK di: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 4. Install ke Tablet

### Aktifkan Developer Mode (sekali saja):
1. Tablet: **Settings → About tablet → tap "Build number" 7x** sampai muncul "You are now a developer!"
2. **Settings → Developer options → aktifkan USB debugging**

### Install via USB:
```bash
# Download platform-tools (adb) dulu
# https://developer.android.com/tools/releases/platform-tools

adb install app/build/outputs/apk/debug/app-debug.apk
```

### Install via file (alternatif):
1. Copy `app-debug.apk` ke tablet (WhatsApp/Drive/email)
2. Buka file → tap → **"Install anyway"** kalau diminta
3. Pastikan **Install unknown apps** diizinkan untuk file manager

---

## ⚙️ 5. Konfigurasi Pertama

### 1. Buka app → beri permission
- **Bluetooth** — wajib
- **Location/Nearby devices** — wajib untuk BLE scan (Android 12+)
- App otomatis jalan di background (ada notifikasi "Niimbot Print Agent")

### 2. Pair printer
1. Tab **Printer** (icon printer)
2. Tap **Scan Devices**
3. Tunggu 10 detik → muncul list perangkat BLE
4. Pilih **NIIMBOT B1 Pro** (atau nama printer kamu)
5. Tap printer → tunggu "Paired with ..." muncul

### 3. Test print
1. Tap **Test Print** (tombol besar)
2. Printer harus mencetak label "TEST LABEL"
3. Kalau sukses → **selesai konfigurasi** 🎉

### 4. Catat alamat server
- Default port: **8080**
- Lihat IP tablet: **Settings → WiFi → info jaringan** (contoh `192.168.1.50`)
- URL server: `http://192.168.1.50:8080`

---

## 🔗 6. Integrasi Cloud POS

App expose REST API di port 8080:

### Endpoint:

| Method | Path | Fungsi |
|---|---|---|
| `POST` | `/print` | Kirim print job (JSON) |
| `POST` | `/print` | Kirim print job (multipart, raw image) |
| `GET` | `/health` | Health check |
| `GET` | `/status` | Status lengkap (printer, queue, stats) |
| `GET` | `/jobs` | List print jobs |
| `GET` | `/jobs/{id}` | Detail print job |
| `POST` | `/test-print` | Cetak label tes |

### Kirim print job (JSON):

```bash
curl -X POST http://192.168.1.50:8080/print \
  -H "Content-Type: application/json" \
  -d '{
    "nama": "Baut M8",
    "hargaJual": 5000,
    "sku": "BRG-0001",
    "stok": 100,
    "satuan": "pcs",
    "qty": 2
  }'
```

**Response:**
```json
{"success": true, "jobId": 12, "message": "Print job queued"}
```

### Kirim print job (raw image, multipart):

```bash
curl -X POST http://192.168.1.50:8080/print \
  -F "file=@label.png" \
  -F "qty=1"
```

### Health check:
```bash
curl http://192.168.1.50:8080/health
# {"status":"ok","printerConnected":true,"queueSize":0,"uptime":120}
```

### Integrasi dari FastAPI (cloud POS):

```python
# print_client.py
import httpx

TABLET_URL = "http://192.168.1.50:8080"  # IP tablet di WiFi toko

async def print_label(nama: str, harga: int, sku: str, stok: int, qty: int = 1):
    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.post(f"{TABLET_URL}/print", json={
            "nama": nama,
            "hargaJual": harga,
            "sku": sku,
            "stok": stok,
            "satuan": "pcs",
            "qty": qty
        })
        return resp.json()
```

---

## 🚀 7. Test End-to-End

### Test 1: LAN langsung (wajib)
1. Tablet & laptop di WiFi yang sama
2. Cek `curl http://<ip-tablet>:8080/health` → `"status":"ok"`
3. Kirim test print → printer cetak

### Test 2: Akses dari luar (cloud)
**Opsi A — Tailscale (disarankan):**
1. Install app **Tailscale** di tablet (Play Store)
2. Login dengan akun Tailscale
3. Catat IP tailnet (format `100.x.x.x`)
4. Cloud POS pakai `http://100.x.x.x:8080/print`

**Opsi B — Cloudflare Tunnel (alternatif):**
1. Install Termux: `pkg install cloudflared`
2. Jalankan: `cloudflared tunnel --url http://localhost:8080`
3. Dapat URL `https://xxx.trycloudflare.com`
4. Cloud POS pakai URL tersebut (ada public HTTPS)

### Test 3: Full flow chat → POS → print
1. User chat ke chatbot: "Tambah Baut M8, beli 3000, jual 5000, stok 100"
2. Chatbot parse → POST ke cloud POS `/api/barang`
3. Cloud POS insert DB → trigger print
4. Cloud POS POST ke tablet `/print`
5. Printer cetak label

---

## 🐛 8. Troubleshooting

### App tidak bisa scan printer
| Kemungkinan | Solusi |
|---|---|
| Bluetooth mati | Nyalakan Bluetooth tablet |
| Permission belum diberi | Settings → Apps → Niimbot Print Agent → Permissions |
| Printer mati / jauh | Pastikan printer nyala, jarak < 5 meter |
| Android 12+ | Beri "Nearby devices" permission |

### Test print gagal
| Kemungkinan | Solusi |
|---|---|
| Printer belum connected | Cek status di tab Dashboard (🔴/🟢) |
| Kertas habis | Ganti kertas label |
| Cover terbuka | Tutup cover printer |
| Baterai printer lemah | Charge printer |
| Label tidak muncul | Cek Logs tab → kirim error ke developer |

### Server tidak bisa diakses
| Kemungkinan | Solusi |
|---|---|
| IP salah | Cek IP tablet di Settings WiFi |
| Port beda | Cek Settings tab → Server Port (default 8080) |
| App di-kill | Cek notifikasi "Niimbot Print Agent" masih ada. Kalau hilang, buka app |
| Firewall router | Pastikan port 8080 diizinkan (kalau akses dari luar LAN) |

### Logcat (untuk report bug):
```bash
adb logcat -s PrintService PrintServer NiimbotBLE
```

---

## 📁 9. Struktur Project

```
niimbot-print-agent/
├── app/
│   ├── build.gradle.kts        # Dependencies (Ktor, Room, Hilt, ZXing)
│   ├── proguard-rules.pro      # R8/ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/niimbot/printagent/
│       │   ├── NiimbotPrintApplication.kt    # Hilt entry point
│       │   ├── ble/
│       │   │   └── NiimbotBluetoothManager.kt # BLE scan/connect/print
│       │   ├── label/
│       │   │   └── LabelGenerator.kt          # Bitmap 584×354 + barcode
│       │   ├── server/
│       │   │   └── PrintServer.kt             # Ktor HTTP server + queue
│       │   ├── service/
│       │   │   └── PrintForegroundService.kt  # Background service
│       │   ├── data/                          # Room: PrintJob, PrinterConfig, PrintLog
│       │   ├── di/                            # Hilt modules
│       │   ├── receiver/
│       │   │   └── BootReceiver.kt            # Auto-start saat boot
│       │   ├── ui/                            # 5 fragments (Dashboard, Printer, Queue, Logs, Settings)
│       │   └── util/
│       │       └── ByteUtils.kt
│       └── res/                               # Layouts, drawables, themes
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── local.properties          # Isi sdk.dir (tidak di-commit)
└── README.md
```

---

## 📌 Catatan Penting

### ⚠️ Protocol Niimbot (best-effort port)
Encoding BLE (`encodeBitmapToPackets`) adalah port dari `niimbluelib` (TypeScript → Kotlin). **Belum diverifikasi ke printer fisik.** Kalau hasil print rusak/blank:
1. Ambil logcat: `adb logcat -s NiimbotBLE PrintServer`
2. Kirim ke developer (Stella) → fix encoding

### 💡 Tips
- **Selalu charger tablet** — service jalan 24/7, baterai cepat habis
- **Whitelist app dari battery optimization** — Settings → Apps → Battery → Unrestricted (biar service gak di-kill)
- **Uji dengan qty besar** — misal 50 print berturut-turut, pastikan queue aman
- **Label 40×30mm** — desain sudah dioptimasi untuk Niimbot B1 Pro (584×354px @ 300dpi)

---

*Dibuat oleh Stella Coder · Agustus 2026*
