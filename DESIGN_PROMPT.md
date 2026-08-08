# 🎨 Niimbot Print Agent — UI Redesign Prompt & Feature Specifications

Dokumen ini berisi spesifikasi fitur dan panduan desain (*UI Redesign Specification*) untuk aplikasi **Niimbot Print Agent**. Dokumen ini dapat langsung digunakan sebagai **Prompt Master** untuk menggenerasi desain UI/UX baru (seperti pada Figma, Stitch, v0.dev, atau AI UI Generator lainnya).

---

## 📌 1. Gambaran Umum Aplikasi (Overview & Concept)

**Niimbot Print Agent** adalah aplikasi Android berbasis *kiosk/agent service* yang berfungsi menghubungkan **Cloud POS System** dengan printer thermal label **Niimbot B1 Pro** via **Bluetooth LE (BLE)** dan **REST API HTTP (Local/LAN)**.

### 🎯 Visi Desain (Design Vision)
- **Estetika**: Modern, Minimalis, Clean Kiosk / POS Companion Style.
- **Tema Visual**: Premium Dark Mode & Sleek Light Mode, aksen neon/vibrant status (Hijau = Connected/Running, Kuning = Connecting/Busy, Merah = Disconnected/Error).
- **Elemen UI**: Cards dengan *soft shadow/glassmorphism*, indikator status real-time berbasis pulsa/animasi *live*, serta navigasi tab/bottom bar yang responsif.
- **Pengalaman Pengguna (UX)**: Dirancang untuk berjalan 24/7 di tablet/HP kasir tanpa memerlukan interaksi pengguna yang rumit. Status koneksi dan log dapat dilihat secara sekilas (*at-a-glance*).

---

## 📱 2. Struktur Navigasi & Layar Utama (Screen Architecture)

Aplikasi memiliki 4 Layar / Tab Utama:
1. **Dashboard & Status Agent** (Layar Utama)
2. **Manajemen Printer BLE** (Koneksi & Scanner)
3. **Antrean & Riwayat Cetak (Print Queue & History)**
4. **Pengaturan & Konfigurasi Jaringan (Settings)**

---

## 🛠️ 3. Detail Rincian Fitur Per Halaman (Feature Breakdown)

### 📊 Tab 1: Dashboard & Agent Monitor
*Fokus: Pemantauan kesehatan service HTTP agent dan status operasional secara live.*

* **Status Service HTTP/Agent**:
  - Indicator Badge (*LIVE / STOPPED*) dengan animasi *pulse/glowing*.
  - IP Address Tablet (WiFi/LAN) dan Port aktif (contoh: `http://192.168.1.50:8080`).
  - Tombol Quick Action: *Start Agent*, *Stop Agent*, dan *Restart Service*.
* **Widget Status Printer Quick View**:
  - Menampilkan nama printer aktif (misal: `Niimbot B1 Pro`).
  - MAC Address & Status BLE (*🟢 Connected / 🔴 Disconnected*).
  - Persentase Baterai Printer (Progress bar & persentase).
* **Ringkasan Statistik Cetak (Daily Metrics)**:
  - Total Job Hari Ini.
  - Jumlah Sukses vs Gagal.
* **Live Activity Console / Stream Log**:
  - Log request HTTP `POST /print` real-time yang masuk dari Cloud POS.
  - Filter log (*Info, Warning, Error*).

---

### 🖨️ Tab 2: Manajemen Printer BLE (Printer & Scanner)
*Fokus: Deteksi, pairing, dan pengaturan komunikasi dengan printer Niimbot B1 Pro.*

* **Scanner Bluetooth LE (BLE)**:
  - Tombol **"Scan Devices"** dengan status animasi putar (*loading/pulse*).
  - Auto-Discovery Protocol Indicator (Menampilkan jenis UUID yang terdeteksi: *Standard FFF0*, *ISSC Transparent UART*, atau *Custom Niimbot e781*).
* **Daftar Perangkat Terdeteksi (Discovered Devices List)**:
  - Card Perangkat: Nama Bluetooth, MAC Address, dan Kekuatan Sinyal (RSSI Indicator).
  - Status Ikon (Tanda terpasang/tersedia).
  - Action Click: *Connect / Pair*.
* **Perangkat Terhubung (Paired Device Detail Card)**:
  - Informasi Detail Printer: Model (`B1 Pro`), MAC (`E7:23:0C:84:16:12`), Nama Perangkat.
  - Tombol **"Disconnect / Unpair"**.
  - Tombol **"Test Print"**: Mengirim cetakan uji (label barcode Code128 test) langsung ke printer.

---

### 📋 Tab 3: Antrean & Riwayat Cetak (Print Queue & Job History)
*Fokus: Transparansi data pencetakan label dan penanganan job yang gagal.*

* **Tab Filter Status**:
  - *All*, *Pending*, *Printing*, *Completed*, *Failed*.
* **Daftar Antrean & Job Card**:
  - Information Chip: ID Transaksi/Job ID, Timestamp (Waktu Cetak), Nama Produk / Item Label.
  - Status Tag (*Badge* dengan warna khusus per status).
  - Preview Gambar Label (Thumbnail bitmap 584×354px).
  - Action Button: *Retry Print* (untuk job yang gagal) dan *Delete Job*.
* **Manajemen Antrean Massal**:
  - Tombol **"Clear History"** (Hapus riwayat lama).
  - Tombol **"Retry All Failed"**.

---

### ⚙️ Tab 4: Pengaturan & Konfigurasi (Settings)
*Fokus: Pengaturan sistem, port server, density cetak, dan perilaku aplikasi.*

* **Konfigurasi HTTP Server Agent**:
  - Input Field: **Server Port** (Default: `8080`).
  - Toggle Switch: **Auto-Start Agent on Device Boot** (Otomatis nyala saat tablet dinyalakan).
  - Toggle Switch: **Keep Awake / Screen Always On** (Cegah tablet tidur).
* **Konfigurasi Printer & Cetak**:
  - Dropdown / Slider: **Print Density / Darkness Level** (Tingkat ketebalan tinta thermal 1-5).
  - Dimension Info: Ukuran Kertas Label Default (misal: `50x30 mm / 584x354 px`).
  - Toggle Switch: **Auto-Reconnect Bluetooth** (Mencoba sambung ulang otomatis jika terputus).
* **Integrasi & Keamanan (API Config)**:
  - Input Field: **API Secret Token** (Opsional untuk otentikasi header request Cloud POS).
  - Endpoint Reference Guide (`POST /print`, `GET /health`, `GET /status`).
* **Sistem & Informasi App**:
  - Versi Aplikasi & Build Number.
  - Tombol **"Export Diagnostic Log"**.

---

## 🎨 4. Prompt Generator UI (Master Prompt for Design Redesign)

> **Dapat langsung dicopy-paste ke AI Tool (Stitch / v0.dev / ChatGPT / Midjourney / Figma Prompt Generator):**

```text
Design a modern, sleek, high-end Android Kiosk/POS Companion mobile app interface for "Niimbot Print Agent" - a local HTTP & Bluetooth LE thermal label print manager for Android tablets/smartphones.

Visual Style & Aesthetics:
- Dashboard Kiosk style with Dark & Light mode compatibility.
- Clean typography (Inter or Outfit font), sharp card layouts with subtle glassmorphism borders and glowing active status badges.
- Primary Color Palette: Deep Slate Navy (#0F172A), Electric Indigo Blue (#4F46E5), Emerald Green for Live/Connected state (#10B981), Amber for Warnings (#F59E0B), and Rose Red for Disconnected/Errors (#F43F5E).

Key Screens to Include:
1. Dashboard / Agent Status Screen:
   - Header with Live HTTP Server Status Card (Showing IP Address 192.168.1.50:8080, pulse glowing green badge, Start/Stop toggle).
   - Quick Printer Status Widget (Connected Niimbot B1 Pro, 85% Battery status, RSSI strength).
   - Daily Analytics Counters (Total Jobs, Success Rate, Errors).
   - Live Scrollable Terminal/Console Request Log at the bottom.

2. BLE Printer Management Screen:
   - Floating/Header "Scan Devices" button with a spinning radar animation.
   - List of Discovered BLE Devices with Name, MAC Address, RSSI signal bar, and "Pair" button.
   - Active Paired Device Details Card showing Auto-Discovery Protocol (ISSC / Custom UUID) and a prominent "Test Print" button.

3. Print Queue & Job History Screen:
   - Filter pills (All, Pending, Printing, Completed, Failed).
   - Rich Job Cards featuring label image thumbnail, Order ID, item count, timestamp, and a "Retry Print" button for failed jobs.

4. Settings & Configuration Screen:
   - Grouped Material settings list with toggles for Auto-Start on Boot, Auto-Reconnect BLE, HTTP Server Port configuration (8080), Print Darkness/Density slider, and API Endpoint docs.

Make the UI feel responsive, polished, intuitive, and professional for retail/restaurant kasir tablet displays.
```

---
*Dokumen ini dibuat secara otomatis untuk pengembangan dan perancangan ulang (redesign) antarmuka Niimbot Print Agent.*
