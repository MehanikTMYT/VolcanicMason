# Блокирующая ошибка клиентской приёмки — 2026-10-25

При подключении реального клиента 26.1.2 / NeoForge 26.1.2.81 к dedicated server и входе в колонию игрок отключается с **Internal server error**. Главное меню и сетевое подключение до входа в колонию работают; проверить GUI Volcanic Mason не удалось.

## Воспроизведение на текущих артефактах

1. Подготовить QA-мир обычным запуском qa/server-smoke.py, затем запустить его отдельную копию с флагом --autonomous. Предыдущий каталог run/server/vmqa-autonomous должен быть перемещён перед повтором.
2. Запустить qa/client-gameplay.py (Xvfb :97, программный OpenGL, клиент VMQAReviewer, loopback 127.0.0.1:25941). Если клиент стартовал раньше сервера, повторить Direct Connection.
3. После подключения выполнить:

~~~sh
python3 qa/rcon.py "gamemode creative VMQAReviewer" "minecolonies colony setowner 1 VMQAReviewer" "tp VMQAReviewer 0.5 101 -3.5 0 20"
~~~

4. При подписке игрока на данные колонии сервер выдаёт NoClassDefFoundError и отключает игрока. Не требуется открывать GUI или нажимать кнопку режима.

## Доказательства

- [Полный серверный лог](evidence/server-autonomous2.log): 10:57:02 ошибка отправки colony view, 10:57:05 отключение игрока.
- [Клиентский лог](evidence/client-gameplay.log).
- [Экран отключения](evidence/client-colony-disconnect.png).
- [Хэши зависимостей повторной проверки](evidence/runtime-artifacts-followup.json) совпадают с исходным набором.

Существенная часть стека:

~~~text
Attempted to load class net.minecraft.client.multiplayer.ClientLevel
which is not present on the dedicated server
java.lang.NoClassDefFoundError: net/minecraft/client/multiplayer/ClientLevel
  ColonyPackageManager.sendColonyViewPackets(ColonyPackageManager.java:207)
  ColonyPackageManager.updateColonyViews(ColonyPackageManager.java:173)
  ColonyPackageManager.addCloseSubscriber(ColonyPackageManager.java:276)
  EventHandler.onEnteringChunk(EventHandler.java:282)
~~~

В доступном исходнике MineColonies строка 207 вызывает ColonyView.serializeNetworkData(...). Это точка для проверки разделения клиентских и серверных ссылок; окончательное исправление зависимости здесь не выполнялось. MineColonies и другие репозитории не изменялись, обход через mixin или добавление клиентских классов на сервер не применялся.

Артефакт MineColonies: minecolonies-1.1.1397-26.1.2.jar, SHA-256 e9085e3eead8d1d0607186a4e32b8ce3f61ff5012f1cd061b14872c628d8a3af.

## Влияние на приёмку

Это блокер клиентской игры в колонии на проверенном наборе зависимостей. Успешные сборка, регистрация и серверные действия гражданина его не устраняют. После исправления зависимости требуется повторить вход владельца в колонию, открытие GUI Volcanic Mason, переключение режимов и синхронизацию настроек.

Клиент после воспроизведения возвращён в главное меню и закрыт кнопкой Quit Game: процесс завершился с кодом 0. Сервер продолжал выполнять AI. После выхода последнего игрока включилась стандартная пауза пустого сервера; незавершённый совмещённый прогон остановлен штатно. В финальном отдельном автономном сценарии пауза отключена параметром pause-when-empty-seconds=-1 только в QA server.properties.
