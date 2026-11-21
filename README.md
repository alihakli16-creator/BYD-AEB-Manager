# AFS Kapat (BYD SEAL U DM-i • DiLink 3.0 / Android 10)

Bu proje, **BYD SEAL U DM-i** ve **DiLink 3.0 (Android 10 - API 29)** sistemine sahip BYD araçlarında, her çalıştırmada otomatik olarak devreye giren **Acil Fren Sistemini (AEB / AFS)** sürücü müdahalesine gerek kalmadan arka planda otomatik kapatmak üzere optimize edilmiş modern Android otomasyon uygulamasıdır.

---

## 📌 Neden "Paket Ayrıştırma Hatası" (Parse Error) Alındı?
Orijinal `BYD-AEB-Manager v2.0` uygulaması `minSdkVersion = 31` (Android 12) hedefiyle derlendiği için; BYD SEAL U DM-i aracınızın DiLink 3.0 işletim sistemi (Android 10 / API 29) bu paketi tanıyamaz ve Android paket yöneticisi `Parse Error` verir.

Bu projede yapılan temel uyarlamalar:
1. **SDK Sürüm Düşürme (Downgrade to API 29):**
   - `minSdkVersion` **31**'den **29**'a (Android 10) indirildi.
   - Android 12'ye özgü `FLAG_MUTABLE` / `FLAG_IMMUTABLE` ve arka plan servis kısıtlamaları Android 10 ile %100 geriye dönük uyumlu hale getirildi.
2. **Kinex Launcher Referanslı Otomasyon:**
   - Araç ekranı açıldığında veya kontak verildiğinde (`BOOT_COMPLETED`, `SCREEN_ON`, `POWER_ON`) arka planda sessizce tetiklenir.
   - Erişilebilirlik Servisi üzerinden ADAS sayfasına gider, AFS anahtarını bulur, kapatır, BYD güvenlik onayını tıklar ve hemen ana ekrana döner.
3. **Kapsamlı Türkçeleştirme:**
   - Arayüz ve arka plan durum bildirimleri tamamen akıcı Türkçe'ye uyarlandı.
4. **Yeniden Adlandırma (Rebranding):**
   - Uygulama adı: **"AFS Kapat"**

---

## 🚀 GitHub Üzerinden Derleme (APKTool veya Android Studio Gerekmez!)

Bu projeyi bilgisayarınızda hiçbir geliştirme aracı kurmadan, doğrudan GitHub'ın kendi sunucularında (GitHub Actions) 1 dakika içinde derleyip hazır APK alabilirsiniz:

1. **GitHub'da Depo Oluşturun:**
   - GitHub hesabınıza girip `New Repository` diyerek `AFS-Kapat` adında yeni bir depo oluşturun.
2. **Kodları Yükleyin:**
   - Bu klasördeki tüm dosyaları (`.github`, `app`, `gradle`, `build.gradle.kts`, `settings.gradle.kts` vb.) reponuza aktarın.
3. **Otomatik Derleme:**
   - Kodları yüklediğiniz anda `.github/workflows/build.yml` dosyası devreye girer ve GitHub Actions üzerinde APK'yı derler.
4. **APK'yı İndirin:**
   - GitHub deponuzda **Actions** sekmesine tıklayın.
   - En son çalışan iş akışına tıklayıp sayfanın altındaki **Artifacts** bölümünden `AFS_Kapat_v2.0_Android10_DiLink3.zip` dosyasını indirin. İçinden çıkan APK araca kurulmaya hazırdır!

---

## 📲 Araca Kurulum ve İzinlerin Tanımlanması

### 1. Yöntem: USB ile Doğrudan Kurulum
- APK dosyasını bir USB belleğe atın.
- BYD multimedya ekranında Dosya Yöneticisi'ne girip APK'yı yükleyin.
- Uygulamayı açın ve ekrandaki 3 izin butonuna basarak onaylayın:
  1. **Erişilebilirlik Servisi (Açık)**
  2. **Üstte Gösterme / Overlay İzni (Açık)**
  3. **Pil Optimizasyonundan Muafiyet (Açık)**

### 2. Yöntem: aShell veya ADB ile Tek Komutla İzin Verme
Eğer aracınızda `aShell` veya kablosuz ADB bağlantısı varsa, aşağıdaki komutları çalıştırarak tüm izinleri tek seferde verebilirsiniz:

```bash
# Overlay (Ekran üzerinde bildirim gösterme) İzni:
pm grant com.byd.afskapat android.permission.SYSTEM_ALERT_WINDOW

# Erişilebilirlik Servisini Otomatik Aktif Etme:
settings put secure enabled_accessibility_services com.byd.afskapat/com.byd.afskapat.service.AfsAccessibilityService
settings put secure accessibility_enabled 1

# Pil optimizasyonundan muaf tutma (Uyku modunda kapanmaması için):
dumpsys deviceidle whitelist +com.byd.afskapat
```

---

## 🛡️ Sürüş Güvenliği Notu
Acil Fren Sistemi (AEB), acil durumlarda otonom frenleme yaparak güvenliği artıran bir sistemdir. Bu uygulama, her sürüşte ayarlar menüsüne girip kapatma zorunluluğunu ortadan kaldırmak amacıyla hazırlanmıştır; sürüş güvenliği her zaman sürücünün sorumluluğundadır.
