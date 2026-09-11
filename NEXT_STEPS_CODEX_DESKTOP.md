# Prompt untuk Codex Desktop

Salin seluruh prompt berikut ke Codex Desktop yang berjalan di Windows, macOS, atau Linux dengan Android Studio, JDK, dan Android SDK tersedia.

```text
Anda sedang melanjutkan migrasi autentikasi aplikasi Android NIIMBOT Print Agent dari integration key ke JWT.

Repositori dan batas lingkup:
- Buka checkout repositori `niimbot-print-agent` pada mesin desktop ini.
- Kerjakan hanya aplikasi mobile Android dan dokumentasi mobile. Jangan mengubah backend atau frontend.
- Diff migrasi JWT yang sudah ada saat ini disengaja. Inspeksi dan pertahankan maksudnya; jangan membuang atau menulis ulang perubahan hanya karena belum di-commit pada checkout awal.
- Pertahankan protokol BLE v4 dan server lokal `/print` tanpa perubahan perilaku atau kontrak.
- Jangan deploy APK ke lingkungan produksi. Instal hanya APK debug ke tablet uji yang disetujui.
- Jangan menampilkan, mencatat, menyimpan, atau memasukkan credential/token nyata ke source, test fixture, log, screenshot, commit, atau laporan.
- Jangan mengubah kontrak backend secara diam-diam. Jika implementasi mobile dan API produksi berbeda, hentikan perubahan kontrak, dokumentasikan request/response/status yang sudah disanitasi, lalu minta keputusan pengguna.
- Jangan commit atau push kecuali pengguna memintanya secara eksplisit.

Kondisi verifikasi dari server/VPS sebelum handoff:
- `XML_PARSE PASS 48`
- `R_ID PASS`
- `R_DRAWABLE PASS`
- header lama `X-Integration-Key` tidak ada
- header `Authorization: Bearer ...` ada
- `git diff --check` lulus
- Gradle tidak dapat dijalankan di VPS karena Java tidak tersedia

Kerjakan urutan berikut.

1. Inspeksi awal dan bukti baseline
- Jalankan `git status --short --branch`, `git diff --stat`, `git diff --name-status`, dan `git diff`.
- Catat branch, commit awal, seluruh file berubah, dan perubahan JWT saat ini.
- Pastikan diff hanya menyentuh mobile Android/dokumentasi mobile. Jangan sentuh backend/frontend.
- Cari pemakaian `X-Integration-Key`, `Authorization`, `Bearer`, `/api/auth/login`, `/api/auth/me`, `/api/integration`, penyimpanan password, dan penyimpanan token.
- Jangan pernah mencetak nilai credential atau token. Sanitasi output yang mungkin memuat data sensitif.

2. Siapkan toolchain Gradle desktop
- Gunakan Project Gradle JDK yang kompatibel dengan versi Android Gradle Plugin proyek, melalui Android Studio atau `JAVA_HOME`/`org.gradle.java.home` lokal.
- Gunakan Android SDK dari Android Studio dan pastikan `adb` tersedia.
- Gunakan Gradle Wrapper proyek, bukan Gradle global:
  - Windows PowerShell: `.\gradlew.bat test` dan `.\gradlew.bat assembleDebug`
  - macOS/Linux: `./gradlew test` dan `./gradlew assembleDebug`
- Catat versi Java, `JAVA_HOME`, versi Gradle Wrapper, lokasi Android SDK yang disanitasi bila perlu, serta exact command/output untuk setiap build/test.

3. Build, test, dan perbaikan minimal berbasis TDD
- Jalankan seluruh unit test dengan `gradlew test` lalu build debug dengan `gradlew assembleDebug`.
- Jika ada compile/test failure, simpan exact error, buat atau ubah satu test kecil yang mereproduksi masalah lebih dahulu, pastikan test gagal karena alasan yang benar, lalu buat diff produksi terkecil agar test lulus.
- Hindari refactor, dependency baru, abstraction baru, formatting massal, dan perubahan di luar masalah yang terbukti.
- Setelah setiap perbaikan, jalankan test terfokus, lalu ulangi seluruh `gradlew test` dan `gradlew assembleDebug`.
- Jalankan `git diff --check` pada hasil akhir.

4. Verifikasi kontrak autentikasi dan keamanan mobile
Verifikasi dengan test otomatis jika praktis, lalu validasi manual yang aman:
- Login memakai `POST /api/auth/login` dengan body yang sesuai kontrak produksi.
- Identitas sesi memakai `GET /api/auth/me`.
- Access token disimpan terenkripsi memakai Android Keystore; token plaintext tidak dipersist ke SharedPreferences, file, database, log, atau backup.
- Password hanya dipakai untuk request login dan tidak pernah dipersist, dipulihkan, atau ditulis ke log.
- Semua route `/api/integration` yang dipakai aplikasi mengirim `Authorization: Bearer <token>`.
- Tidak ada request yang mengirim `X-Integration-Key`.
- HTTP 401 dari route terautentikasi menghapus sesi/token lokal, mengubah UI ke logged-out, dan meminta login ulang tanpa loop atau crash.
- Logout menghapus token serta identitas sesi lokal dan tidak mengganggu server lokal `/print` atau BLE.
- Pastikan tidak ada token/credential dalam exact outputs yang dikumpulkan; ganti nilai sensitif dengan `[REDACTED]` tanpa menghilangkan nama header, endpoint, status, atau struktur error yang diperlukan.

5. Uji API produksi tanpa mengubah inventori nyata
- Minta credential admin dan karyawan melalui mekanisme privat/interaktif; jangan meminta pengguna menaruhnya dalam source atau chat/log yang disimpan.
- Uji login admin dan karyawan terhadap API produksi, lalu `GET /api/auth/me`, menggunakan aplikasi atau request sementara yang tidak merekam secret.
- Untuk kedua role, verifikasi endpoint baca yang dipakai aplikasi dan otorisasi route `/api/integration` tanpa operasi tulis terhadap inventori nyata.
- Jangan membuat, mengubah, mengurangi, menambah, atau menghapus stok/SKU/produk nyata.
- Jika verifikasi mutasi benar-benar diperlukan, jelaskan tepat endpoint, payload tersanitasi, efek, dan rencana pemulihan; gunakan hanya SKU disposable/test setelah pengguna memberi persetujuan eksplisit untuk mutasi tersebut. Tanpa persetujuan eksplisit, lewati mutasi dan tandai sebagai belum diuji.
- Bedakan hasil admin dan karyawan, termasuk status HTTP yang diharapkan, tanpa membuka credential atau token.

6. Instal dan uji pada tablet
- Pastikan tablet uji yang benar terdeteksi dengan `adb devices -l`; catat serial secara disanitasi bila dianggap sensitif.
- Temukan APK debug dari output Gradle dan instal dengan perintah `adb install -r <path-ke-debug-apk>` yang sesuai. Jangan instal release dan jangan deploy ke produksi.
- Catat exact command/output instalasi dan identitas package/version yang aman.
- Pada tablet, verifikasi:
  1. Settings menampilkan state logged-out, login admin/karyawan bekerja, identitas/role tampil benar, dan logout menghapus sesi.
  2. Flow label berbasis POS dapat mengambil data produk dan menyiapkan label tanpa mengubah inventori nyata.
  3. Server lokal menjawab `/health` dan `/status` dengan hasil benar.
  4. Server lokal dapat dijangkau melalui alamat Tailscale tablet dari client uji yang diotorisasi.
  5. NIIMBOT B1 Pro dapat ditemukan/terhubung dan mencetak label uji melalui protokol BLE v4 yang sudah ada.
  6. Server lokal `POST /print` tetap bekerja sesuai kontrak lama yang dipertahankan.
  7. Setelah app force-stop/restart atau reboot tablet bila aman, token tetap dapat dipulihkan dari penyimpanan terenkripsi, `/api/auth/me` memulihkan sesi, dan password tidak ada di storage/UI/log.
  8. Dengan token expired/revoked yang aman untuk akun uji, request menerima 401, token/sesi lokal dibersihkan, UI kembali logged-out, dan pengguna dapat login ulang. Jangan mengubah waktu perangkat atau akun produksi jika berisiko mengganggu pemakaian nyata.
- Sanitasi logcat dan network capture. Jangan kumpulkan body/header yang membuka password atau token.

7. Bukti akhir
- Jalankan kembali:
  - `git status --short --branch`
  - `git diff --stat`
  - `git diff --name-status`
  - `git diff --check`
  - `gradlew test`
  - `gradlew assembleDebug`
- Laporkan exact command dan output perangkat/build/test yang relevan. Untuk output panjang, sertakan lokasi artefak log lokal serta ringkasan PASS/FAIL, tetapi tetap sertakan exact failure lines dan exit code. Pastikan artefak log tidak berisi secret.
- Laporkan semua file yang berubah dan alasan singkat tiap file.
- Laporkan item yang tidak dapat diverifikasi, alasan, dan langkah manual berikutnya. Jangan menyatakan PASS bila langkah dilewati.

Checklist penerimaan akhir:
- [ ] Diff JWT awal sudah diperiksa dan maksudnya dipertahankan.
- [ ] BLE v4 tetap bekerja dengan NIIMBOT B1 Pro.
- [ ] Server lokal `/print`, `/health`, dan `/status` tetap bekerja.
- [ ] Tailscale reachability ke server lokal tablet terverifikasi.
- [ ] `gradlew test` lulus.
- [ ] `gradlew assembleDebug` lulus.
- [ ] `git diff --check` lulus.
- [ ] Login `POST /api/auth/login` terverifikasi untuk admin dan karyawan.
- [ ] Sesi `GET /api/auth/me` terverifikasi untuk admin dan karyawan.
- [ ] Token terenkripsi dengan Android Keystore dan bertahan setelah restart.
- [ ] Password tidak dipersist atau dicatat.
- [ ] Semua request `/api/integration` memakai Bearer.
- [ ] Tidak ada `X-Integration-Key`.
- [ ] Logout dan HTTP 401 membersihkan sesi serta kembali ke state logged-out.
- [ ] Flow label berbasis POS terverifikasi tanpa mutasi inventori nyata.
- [ ] Tidak ada mutasi produksi tanpa persetujuan eksplisit; jika disetujui, hanya SKU disposable/test yang dipakai dan hasil/pemulihan didokumentasikan.
- [ ] APK debug terinstal pada tablet uji yang benar.
- [ ] Tidak ada credential/token dalam source, diff, log, screenshot, atau laporan.
- [ ] Backend/frontend tidak berubah dan kontrak backend tidak diubah diam-diam.
- [ ] Exact command/device outputs serta daftar file berubah sudah dikumpulkan.

Instruksi rollback:
- Sebelum mengubah file, simpan branch dan SHA awal: `git branch --show-current` dan `git rev-parse HEAD`.
- Jangan memakai `git reset --hard`, `git clean`, force push, atau perintah destruktif pada worktree yang berisi diff handoff.
- Jika perubahan desktop belum di-commit, pulihkan hanya baris/file yang Anda ubah sendiri dengan reverse patch terarah atau fitur rollback IDE; jangan hapus diff JWT awal milik handoff.
- Jika perubahan desktop sudah di-commit atas permintaan pengguna, rollback dengan commit baru `git revert <sha-commit-desktop>` setelah konfirmasi pengguna; jangan rewrite history.
- Untuk rollback tablet, instal ulang APK debug terakhir yang sebelumnya diketahui baik dengan `adb install -r <apk-sebelumnya>` setelah konfirmasi pengguna. Jika skema penyimpanan sesi tidak kompatibel, logout/clear session melalui UI terlebih dahulu; gunakan `adb shell pm clear <package>` hanya dengan persetujuan karena menghapus seluruh data aplikasi.
- Setelah rollback, ulangi smoke test BLE v4, `/print`, `/health`, `/status`, Settings login/logout, dan catat exact output.

Berikan laporan akhir ringkas tetapi lengkap: status tiap checklist, exact commands dan outputs tersanitasi, file berubah, masalah yang diperbaiki beserta test reproduksi, item belum teruji, dan langkah rollback yang benar-benar berlaku untuk hasil ini.
```
