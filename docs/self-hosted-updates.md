# Self-hosted Android updates

The application checks this public, read-only manifest:

`https://update.lithiaproject.site/latest.json`

An HTTP `404` means no update has been published yet and is handled silently during the automatic startup check.

## Manifest contract

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "minimumVersionCode": 1,
  "required": false,
  "apkUrl": "https://update.lithiaproject.site/releases/2/app-release.apk",
  "sha256": "64 lowercase or uppercase hexadecimal characters",
  "sizeBytes": 19340640,
  "releaseNotes": [
    "Ringkasan perubahan pertama",
    "Ringkasan perubahan kedua"
  ]
}
```

The client rejects metadata unless:

- `versionCode`, `minimumVersionCode`, and `sizeBytes` are positive;
- `minimumVersionCode` is not greater than `versionCode`;
- `sha256` contains exactly 64 hexadecimal characters;
- the APK URL is exactly `https://update.lithiaproject.site/releases/{versionCode}/app-release.apk` with no query or fragment.

`required: true` removes the in-app postpone action. It does not silently install an APK; Android still controls installation consent.

## Release order

1. Increase `versionCode` and set `versionName` in `app/build.gradle.kts`.
2. Build a production APK with the same application ID and signing certificate as the installed production version.
3. Keep the signing key, passwords, tokens, source, and debug artifacts outside the public DocumentRoot and Git.
4. Upload the APK first to `/var/www/lithia-updates/public/releases/{versionCode}/app-release.apk`. Never overwrite an existing version URL.
5. Calculate SHA-256 and the exact byte length from the uploaded APK.
6. Create and validate `latest.json` outside the DocumentRoot.
7. Publish `latest.json` last using a temporary file and atomic rename.
8. From another device, verify the public APK size/hash and perform a real upgrade test.

Use `Cache-Control: no-store` for `latest.json`. Use `Cache-Control: public, max-age=31536000, immutable` for versioned APKs.

## Signing transition

The current Gradle configuration does not contain a production signing key. Before the first real release, create and securely back up a production keystore. A debug-signed installation cannot be upgraded by an APK signed with a different production certificate; that transition requires a one-time uninstall/reinstall and removes local application data.
