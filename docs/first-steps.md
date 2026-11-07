# Первые действия в IntelliJ IDEA

Откройте корневой pom.xml как Maven-проект либо обновите существующий проект действием Reload All Maven Projects в панели Maven. Выберите JDK 21 как SDK проекта и Java для Maven.

В панели Maven должны появиться common, discovery-server, api-gateway, hotel-service и booking-service. common является библиотекой. Java-пакеты com.example сохранены: они не обязаны совпадать с Maven groupId.

Папка tools содержит отдельный GenerateKeys.java; предупреждение о нахождении вне source root для этого файла ожидаемо. Ключи пока не создавались.

Следующий шаг после ознакомления с кодом — ваша отдельная проверка сборки и тестов по docs/manual.md. Автоматически сборка и запуск при подготовке файлов не выполнялись.
