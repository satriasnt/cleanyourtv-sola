# Cara pasang CleanYourTV Sola

Launcher Android TV / Google TV bergaya Apple TV, berbasis [CleanYourTV](https://github.com/Gathin23/cleanyourtv) karya Gathin, dengan tambahan:

- Kartu **jadwal sholat** (lokasi dipilih dari kode pos, dihitung langsung di TV)
- **Pengingat adzan**: banner di atas aplikasi apa pun, bisa dengan suara adzan
- **Kalender** dengan tanggal Hijriah
- **Jadwal bola** tim favorit (FotMob atau Gemini)
- Gaya kartu kaca buram, dan tiap kartu bisa disembunyikan

Butuh TV Android 8 ke atas, dan sebuah HP Android (pakai Termux) atau komputer untuk menjalankan adb. Cukup sekali saat memasang.

> Launcher ini menggantikan Home bawaan TV. Ikuti langkahnya pelan-pelan, dan simpan nama launcher bawaanmu (langkah 3) supaya bisa dikembalikan kapan saja.

## 1. Nyalakan ADB di TV

1. Buka **Settings → Device Preferences → About**, tekan **Build** 7 kali sampai muncul "You are now a developer".
2. Kembali ke **Device Preferences → Developer options**, nyalakan **USB debugging** (di sebagian TV ada juga **Network debugging** / **ADB over network**, nyalakan juga).
3. Catat IP TV di **Settings → Network & Internet →** nama Wi-Fi kamu.

HP/komputer dan TV harus tersambung ke Wi-Fi yang sama.

## 2. Siapkan adb

**Pakai HP (Termux).** Pasang Termux dari F-Droid (versi Play Store sudah lama tidak diperbarui), lalu:

```
pkg install android-tools
termux-setup-storage
```

Izinkan akses penyimpanan saat diminta. File yang kamu unduh ada di `~/storage/downloads/`.

**Pakai komputer.** Pasang [Android platform-tools](https://developer.android.com/tools/releases/platform-tools), lalu buka Terminal / Command Prompt di folder tempat APK diunduh.

Unduh `tvhome-widget.apk` dari halaman **Releases** repo ini.

## 3. Sambungkan dan cek launcher bawaan

```
adb connect <ip-tv>:5555
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Saat pertama kali, di layar TV muncul pertanyaan izin debugging: centang **Always allow**, lalu **OK**.

Baris terakhir hasil perintah kedua adalah launcher bawaan TV-mu, misalnya `com.google.android.tvlauncher/...`, `com.google.android.apps.tv.launcherx/...` (Google TV), atau `com.mitv.tvhome/...` (Xiaomi). **Catat nama paketnya** (bagian sebelum `/`).

## 4. Pasang dan jadikan Home

Di Termux (ganti path-nya kalau pakai komputer):

```
adb install -r ~/storage/downloads/tvhome-widget.apk
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
adb shell pm disable-user --user 0 <launcher-bawaan>
```

Ganti `<launcher-bawaan>` dengan nama paket dari langkah 3. Tekan tombol **Home** di remote untuk mengecek.

## 5. Beri izin (untuk pengingat adzan)

```
adb shell appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow
adb shell appops set com.gathin.tvhome SCHEDULE_EXACT_ALARM allow
adb shell appops set com.gathin.tvhome APP_AUTO_START allow
```

Yang pertama supaya banner adzan bisa tampil di atas aplikasi lain, yang kedua supaya tepat waktu, yang ketiga supaya launcher ikut menyala setelah TV restart (di sebagian TV perintah ini tidak dikenal; abaikan saja kalau error).

## 6. Suara adzan (opsional)

Launcher ini **tidak menyertakan** suara adzan. Siapkan file MP3 adzan pilihanmu sendiri, beri nama `adzan.mp3`, lalu:

```
adb push ~/storage/downloads/adzan.mp3 /data/local/tmp/adzan.mp3
adb shell 'content write --uri content://com.gathin.tvhome.theme/adzan.mp3 < /data/local/tmp/adzan.mp3; rm /data/local/tmp/adzan.mp3'
```

Mau adzan khusus Subuh? Ulangi dengan nama `adzan_subuh.mp3`. Kalau tidak ada, Subuh memakai `adzan.mp3`.

Supaya ringan, kecilkan dulu file-nya (misalnya jadi mono 48 kbps, sekitar 1,5 MB untuk adzan 4 menit).

## 7. Atur di TV

Buka **Launcher settings** dari Home:

- **Widget Home**: gaya kartu, ketebalan latar, tampil/sembunyi tiap kartu.
- **Jadwal sholat & adzan**: lokasi, mode pengingat (banner / nada / suara adzan), waktu sholat mana yang diingatkan, dan tombol **Tes pengingat sekarang**.
- **Jadwal bola**: tim favorit dan sumber jadwal. FotMob langsung jalan; Gemini butuh API key gratis dari aistudio.google.com/apikey (petunjuknya muncul di layar). Mengetik key paling enak lewat aplikasi Google TV di HP.

Pengingat adzan hanya bunyi saat TV menyala. Tekan **Home** untuk menghentikan adzan yang sedang diputar.

## Kembali ke launcher bawaan

```
adb shell pm enable <launcher-bawaan>
adb shell pm uninstall com.gathin.tvhome
```

## Catatan

- **Xiaomi Mi TV baru** (sebagian model) hanya mengizinkan launcher tertentu jadi Home: perintah di langkah 4 terlihat berhasil, tapi tombol Home tetap membuka launcher Xiaomi. Kalau itu terjadi, jalankan perintah "Kembali ke launcher bawaan" di atas. Mi TV 4A sudah dicoba dan aman.
- Data jadwal bola dari FotMob memakai API tidak resmi, jadi suatu saat bisa berhenti. Pindah ke Gemini di Launcher settings kalau itu terjadi.
- Key Gemini, lokasi sholat, dan tim favorit disimpan di TV-mu sendiri, tidak dikirim ke mana pun selain ke layanan yang kamu pilih.

## Lisensi

MIT, sama seperti CleanYourTV. Terima kasih untuk [Gathin](https://github.com/Gathin23/cleanyourtv) atas launcher aslinya. Font Outfit memakai SIL Open Font License.
