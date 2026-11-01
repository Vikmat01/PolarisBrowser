# Polaris Browser

Браузер для Android на системном WebView в стиле Polaris: жидкое стекло, северное сияние на главной, блокировка рекламы и юзерскрипты.

## Возможности

- **Жидкое стекло.** Нижняя панель и меню преломляют страницу под собой (AGSL-шейдер, Android 13+), блик следует за пальцем.
- **Главная.** Анимированное северное сияние или своё фото, тема по восходу и закату, 8 цветов оформления.
- **Блокировка рекламы** по EasyList + RuAdList, скрытие рекламных блоков, исключения для сайтов.
- **Вкладки и инкогнито.** Отдельный профиль, данные стираются при закрытии, скриншоты блокируются.
- **Расширения.** Юзерскрипты (`.user.js`, совместимость с Tampermonkey) и стили (`.user.css`) с Greasy Fork и UserStyles.world.
- **Режим чтения** (Mozilla Readability), видеоплеер со скоростью, жестами и «картинкой в картинке».
- **Закладки, история, загрузки**, поиск по странице, версия для ПК.
- **Обновления** приходят прямо из GitHub Releases.

## Установка

Скачайте APK из [Releases](../../releases/latest). Нужен Android 10+; стекло-линза работает на Android 13+.

## Сборка

```bash
./gradlew assembleRelease
```

Без `keystore.properties` релиз подписывается отладочным ключом. Для своей подписи положите в корень `keystore.properties`:

```
storeFile=polaris.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Выпуск новой версии

1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`, описать изменения в `release-notes/vX.Y.md`.
2. Закоммитить это в `main` (или отправить метку `vX.Y`).
3. GitHub Actions соберёт подписанный APK и опубликует релиз, браузеры сами предложат обновиться.

Нужны секреты репозитория (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Лицензии

Режим чтения использует [Readability.js](https://github.com/mozilla/readability) (Apache-2.0).
