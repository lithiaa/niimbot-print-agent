# Next Steps: Build & Install New APK on Tablet

Pull latest code, build APK, install on tablet, verify E2E print.

---

## 1. Pull latest code

```bash
cd ~/niimbot-print-agent
git pull origin main
```

Verify commit `d47fc15` is included:
```bash
git log --oneline -3
# d47fc15 fix: rewrite BLE protocol ke v4 validated (B1 Pro) — frame 55 55, RLE rows, status poll
```

---

## 2. Open in Android Studio

```bash
# From project root
open -a "Android Studio" .      # macOS
# or
android-studio .                # Linux
# or start Android Studio → File → Open → select ~/niimbot-print-agent
```

Wait for Gradle sync to complete (bottom status bar: "Gradle sync finished").

---

## 3. Connect tablet via USB

- Enable **USB Debugging** on tablet:
  - Settings → About tablet → Tap "Build number" 7× → Developer options → USB debugging ON
- Connect USB cable
- Verify:
```bash
adb devices
# Should show: <serial>   device
```

---

## 4. Build & Install APK

### Option A: Build APK via Android Studio UI
1. **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. Wait for build → notification "APK(s) generated" → click **locate**
3. Install:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option B: Build & Install via Gradle (one command)
```bash
./gradlew installDebug
```

---

## 5. Launch & Configure on Tablet

1. Open **Niimbot Print Agent** app on tablet
2. **Dashboard** tab → tap **Start Agent** (if stopped)
3. **Settings** tab → verify:
   - **Server Port**: `8080`
   - **BLE**: printer paired & connected (MAC `E7:23:0C:84:16:12`)
4. **Dashboard** → Status HTTP = **LIVE**

---

## 6. Verify E2E from Server

```bash
# Direct print test
curl -X POST http://100.110.47.31:8080/print \
  -H "Content-Type: application/json" \
  -d '{"nama":"TEST E2E","hargaJual":25000,"sku":"E2E001","stok":10,"qty":1}'

# Expected: {"success":true,"jobId":1}
```

```bash
# Check job status
curl http://100.110.47.31:8080/jobs
# Should show job with status "done"
```

```bash
# Chatbot print test (via POS backend)
curl -X POST http://localhost:8000/api/chatbot/ \
  -H "Content-Type: application/json" \
  -d '{"command":"cetak label id=3 qty=1"}'
```

---

## 7. Success Criteria

| Check | Expected |
|-------|----------|
| `/status` | `"printer":{"connected":true},"queue":{"pending":0,"printing":0,"failed":0}` |
| `/jobs` | Latest job has status `done` (not `printing`/`failed`) |
| Printer | **Physical label printed** with correct nama, harga, SKU |

---

## 8. If Issues

| Symptom | Fix |
|---------|-----|
| `printerConnected: false` | Re-pair BLE in Android Bluetooth settings, restart app |
| `/print` returns error | Check tablet logs: `adb logcat -s NiimbotBLE` |
| Job stuck `printing` | Verify new APK installed (check version in Settings), restart app service |
| `adb install` fails | `adb uninstall com.niimbot.printagent` first, then reinstall |

---

## 9. After Success

- Tell Stella "APK installed & print works" → she will mark E2E complete
- POS chatbot `cetak label` now fully operational via Tailscale → tablet → printer 🎉