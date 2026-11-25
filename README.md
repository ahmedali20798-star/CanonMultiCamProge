# CanonMultiCamPro (v3.0.0)

تطبيق أندرويد احترافي للتحكم المتزامن في كاميرات **Canon Cinema EOS** و**EOS R** عبر الشبكة والـ USB، مبني وفق **Clean Architecture** و**Jetpack Compose**.

## البروتوكولات المدعومة

* **Canon CCAPI (حتى v1.4.0f):** التحكم الكامل في التصوير الفوتوغرافي والفيديو، البث الحي المستمر (`Multipart MJPEG` عبر `shooting/liveview/scroll`)، تسلسل الغالق اليدوي (`full_press` / `release`)، والتركيز باللمس (`Touch AF`) وفق أبعاد البث الحي الفعلية.
* **Canon XC Control Protocol (`/-wvhttp-01-/`):** دعم وضع السينما لكاميرا **EOS R5 C** عبر نقاط النهاية الرسمية (`open.cgi`، `info.cgi`، `control.cgi`، `image.cgi`) مع مصادقة **HTTP Digest (RFC 2617)** شاملةً لـ Query String.
* **Wi-Fi PTP/IP & USB PTP:** دعم الاتصال المباشر عبر الشبكة اللاسلكية أو كابل USB مع إدارة دورة حياة الجلسة بأمان (`NonCancellable` release).

## جدول توافق الكاميرات

| الموديل | البروتوكول | حالة تفعيل CCAPI |
| :--- | :--- | :--- |
| **EOS R1 / R5 Mark II / R6 Mark III** | CCAPI v1.4.0 | مفعّلة مسبقاً من المصنع (Pre-Activated) |
| **EOS R5 C (Video Mode)** | Canon XC Protocol | مدمج في وضع Cinema |
| **EOS R5 C (Photo Mode)** | CCAPI v1.1.0 | يتطلب أداة التفعيل (CCAPI Activation Tool) |
| **EOS R6 Mark II / R8** | CCAPI v1.2.0 | يتطلب أداة التفعيل (CCAPI Activation Tool) |
| **EOS R6 / R7** | CCAPI v1.0 / v1.1 | يتطلب أداة التفعيل (CCAPI Activation Tool) |

## البناء والاختبار محلياً والرفع إلى GitHub

```bash
# التأكد من تضمين ملفات Gradle Wrapper قبل الرفع
git add -f gradlew gradlew.bat gradle/wrapper/

# تشغيل الفحص والاختبارات وبناء الحزمة محلياً
./gradlew detekt testDebugUnitTest assembleDebug
```
