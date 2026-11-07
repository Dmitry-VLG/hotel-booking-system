# Сборка, запуск и ручная проверка

Команды выполняются из корня готового Maven-проекта в PowerShell. Для обычного запуска нужны JDK 21 и Maven 3.9; Docker и внешняя база данных не требуются.

В IntelliJ IDEA выберите JDK 21 для проекта, модулей, Maven Importing/Runner и конфигураций запуска. Проверьте `java -version` и `mvn -version` в новой вкладке терминала: обе команды должны использовать Java 21. Если терминал использует другую версию, настройте его окружение или один раз исправьте пользовательские переменные Windows `JAVA_HOME` и `PATH`. Настройка Project SDK и Java командной строки может различаться.

## Шаг 1 Открыть проект

В IntelliJ IDEA откройте корневой pom.xml как проект, выберите JDK 21 и выполните Reload Maven. Если редактор не видит getId/setId и другие методы Lombok, включите Settings → Build Execution Deployment → Compiler → Annotation Processors → Enable annotation processing. Сборка Maven тоже должна использовать JDK 21.

Проверьте, что рядом с корневым pom находятся пять основных модулей из modules и дополнительный integration-tests для профиля e2e; common — библиотека. Java-классы должны лежать в src/main/java, тесты — в src/test/java, YAML и db/migration — в src/main/resources соответствующего сервиса. Не переносите все классы в один модуль.

## Шаг 2 Создать локальные ключи

Откройте PowerShell в своей папке hotel-booking-system. В примере используется путь ниже; если вы выбрали другой, замените его во всех командах cd.

```powershell
cd 'C:\Users\Dmitry\Desktop\РПО\IDEA\hotel-booking-system'
java .\tools\GenerateKeys.java
```

Java 21 умеет выполнить одиночный исходный файл. Должны появиться keys/private.pem и keys/public.pem. Приватный ключ нельзя публиковать; keys уже указан в .gitignore. Повторная генерация при существующих файлах намеренно завершается ошибкой и ничего не заменяет. Не меняйте пару ключей между запусками трёх приложений.

## Шаг 3 Запустить тесты и собрать проект

```powershell
mvn clean verify
```

Первый раз Maven скачает зависимости. Дождитесь BUILD SUCCESS для всех модулей. Если тест упал, сначала прочитайте первую причину в отчёте соответствующего модуля target/surefire-reports. Не добавляйте -DskipTests, чтобы скрыть падение.

После успешной сборки можете добавить Maven Wrapper:

```powershell
mvn wrapper:wrapper -Dmaven=3.9.11
```

После этого будущая сборка выполняется так:

```powershell
.\mvnw.cmd clean verify
```

Wrapper скачивает свою версию Maven, но установленная Java всё равно нужна. Сохраняйте .mvn, mvnw и mvnw.cmd в репозитории.

## Шаг 4 Открыть четыре терминала и запустить приложения

Каждый процесс занимает свой терминал. Переменные окружения одного окна не появляются автоматически в другом.

В первом терминале:

```powershell
cd 'C:\Users\Dmitry\Desktop\РПО\IDEA\hotel-booking-system'
java -jar .\discovery-server\target\discovery-server-1.0-SNAPSHOT.jar
```

Откройте http://127.0.0.1:8761 в браузере. После появления страницы Eureka запускайте остальные приложения.

Во втором терминале:

```powershell
cd 'C:\Users\Dmitry\Desktop\РПО\IDEA\hotel-booking-system'
$env:PUBLIC_KEY = [System.Uri]::new((Resolve-Path '.\keys\public.pem').Path).AbsoluteUri
$env:DEMO_ENABLED = 'true'
java -jar .\hotel-service\target\hotel-service-1.0-SNAPSHOT.jar
```

В третьем терминале:

```powershell
cd 'C:\Users\Dmitry\Desktop\РПО\IDEA\hotel-booking-system'
$env:PUBLIC_KEY = [System.Uri]::new((Resolve-Path '.\keys\public.pem').Path).AbsoluteUri
$env:PRIVATE_KEY = [System.Uri]::new((Resolve-Path '.\keys\private.pem').Path).AbsoluteUri
$env:DEMO_ENABLED = 'true'
$env:DEMO_ADMIN_PASSWORD = 'Local-Admin-2026!'
$env:DEMO_USER_PASSWORD = 'Local-User-2026!'
java -jar .\booking-service\target\booking-service-1.0-SNAPSHOT.jar
```

Эти пароли — только выбранные для локальной демонстрации значения. Если поменяете их здесь, поменяйте и в запросах ниже. Пароли не нужно вставлять в YAML или коммитить.

В четвёртом терминале:

```powershell
cd 'C:\Users\Dmitry\Desktop\РПО\IDEA\hotel-booking-system'
$env:PUBLIC_KEY = [System.Uri]::new((Resolve-Path '.\keys\public.pem').Path).AbsoluteUri
java -jar .\api-gateway\target\api-gateway-1.0-SNAPSHOT.jar
```

В Eureka должны зарегистрироваться BOOKING-SERVICE, HOTEL-SERVICE и API-GATEWAY. Обновление registry занимает некоторое время. Проверяйте готовность, а не повторяйте создание брони с разными ключами во время старта.

## Шаг 5 Проверить готовность

В пятом терминале PowerShell, который будет использоваться для HTTP-запросов:

```powershell
$base = 'http://127.0.0.1:8080'
Invoke-RestMethod 'http://127.0.0.1:8081/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:8082/actuator/health'
Invoke-RestMethod "$base/actuator/health"
```

Ожидается status UP. Если через Gateway пока возвращается 503, проверьте регистрацию и дождитесь обновления discovery. Swagger UI открывается напрямую: http://127.0.0.1:8081/swagger-ui/index.html и http://127.0.0.1:8082/swagger-ui/index.html. В Authorize вставляйте только accessToken; схема Bearer добавит префикс сама.

## Шаг 6 Войти пользователем

```powershell
$loginJson = @{ username = 'alice'; password = 'Local-User-2026!' } | ConvertTo-Json
$alice = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $loginJson
$headers = @{ Authorization = "Bearer $($alice.accessToken)" }
$start = [DateTime]::UtcNow.Date.AddDays(10).ToString('yyyy-MM-dd')
$end = [DateTime]::UtcNow.Date.AddDays(12).ToString('yyyy-MM-dd')
$hotels = Invoke-RestMethod -Uri "$base/api/hotels" -Headers $headers
$firstHotel = $hotels.content[0]
$rooms = Invoke-RestMethod -Uri "$base/api/rooms/recommend?hotelId=$($firstHotel.id)&startDate=$start&endDate=$end" -Headers $headers
$firstRoom = $rooms.content[0]
$rooms.content | Format-Table
```

В первом demo-отеле ожидаются два доступных номера; ремонтируемый 103 в рекомендациях отсутствует. Даты вычисляются заново относительно текущей UTC-даты, поэтому пример не устаревает.

## Шаг 7 Создать бронь и повторить тот же запрос

```powershell
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$body = @{ autoSelect = $false; roomId = $firstRoom.id; startDate = $start; endDate = $end } | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Uri "$base/api/bookings" -Headers $headers -ContentType 'application/json' -Body $body
$booking
$replayed = Invoke-RestMethod -Method Post -Uri "$base/api/bookings" -Headers $headers -ContentType 'application/json' -Body $body
$replayed.id -eq $booking.id
```

Ожидается CONFIRMED, а сравнение возвращает True. Вторая отправка использует прежний ключ. Если создать новый ключ, получится новое намерение, которое уже должно конфликтовать с занятым интервалом.

```powershell
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
try {
    Invoke-RestMethod -Method Post -Uri "$base/api/bookings" -Headers $headers -ContentType 'application/json' -Body $body
} catch {
    [int]$_.Exception.Response.StatusCode
}
```

Ожидается 409. В истории может появиться CANCELLED-запись неудачного намерения; подтверждённого дубля быть не должно.

## Шаг 8 Проверить автоподбор

```powershell
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$autoBody = @{ autoSelect = $true; hotelId = $firstHotel.id; roomId = -999; startDate = $start; endDate = $end } | ConvertTo-Json
$automatic = Invoke-RestMethod -Method Post -Uri "$base/api/bookings" -Headers $headers -ContentType 'application/json' -Body $autoBody
$automatic
```

roomId=-999 игнорируется. Должен быть выбран другой свободный номер первого отеля. Если вы уже заняли оба номера предыдущими экспериментами, ответ 409 будет закономерен; сначала отмените свои подтверждённые брони.

## Шаг 9 Проверить историю права и статистику

```powershell
Invoke-RestMethod -Uri "$base/api/bookings?page=0&size=20" -Headers $headers
$bobJson = @{ username = 'bob'; password = 'Local-User-2026!' } | ConvertTo-Json
$bob = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $bobJson
try {
    Invoke-RestMethod -Uri "$base/api/bookings/$($booking.id)" -Headers @{ Authorization = "Bearer $($bob.accessToken)" }
} catch {
    [int]$_.Exception.Response.StatusCode
}
$adminJson = @{ username = 'admin'; password = 'Local-Admin-2026!' } | ConvertTo-Json
$admin = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $adminJson
$adminHeaders = @{ Authorization = "Bearer $($admin.accessToken)" }
Invoke-RestMethod -Uri "$base/api/admin/statistics/occupancy?hotelId=$($firstHotel.id)&from=$start&to=$end" -Headers $adminHeaders
```

Bob получает 404 при запросе чужой брони. Для двух подтверждённых двухдневных броней ожидаются по две занятые ночи у соответствующих номеров. Попытка Alice получить /api/users даст 403. Любой пользовательский токен, включая ADMIN, не должен позволять POST /api/rooms/{id}/release через Gateway.

## Шаг 10 Отменить брони

```powershell
Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$base/api/bookings/$($booking.id)" -Headers $headers | Select-Object StatusCode
Invoke-WebRequest -UseBasicParsing -Method Delete -Uri "$base/api/bookings/$($automatic.id)" -Headers $headers | Select-Object StatusCode
Invoke-RestMethod -Uri "$base/api/bookings/$($booking.id)" -Headers $headers
```

В нормальном контуре DELETE возвращает 204, GET показывает CANCELLED и DONE. При неудачном освобождении DELETE возвращает 202 и REQUIRED; восстановитель повторит освобождение. Исторический timesBooked после обычной отмены сохраняется, а номер становится свободным на даты.

## Шаг 11 Проверить конкуренцию через весь контур

Этот пример использует PowerShell Start-Job, который доступен и в Windows PowerShell 5.1. Создаётся 20 запросов с разными ключами. Все используют одного пользователя, один номер и одинаковые новые даты. Не запускайте этот опыт одновременно с ручными бронированиями на те же даты.

```powershell
$parallelStart = [DateTime]::UtcNow.Date.AddDays(100).ToString('yyyy-MM-dd')
$parallelEnd = [DateTime]::UtcNow.Date.AddDays(102).ToString('yyyy-MM-dd')
$jobs = 1..20 | ForEach-Object {
    Start-Job -ArgumentList $base, $alice.accessToken, $firstRoom.id, $parallelStart, $parallelEnd -ScriptBlock {
        param($api, $token, $roomId, $startDate, $endDate)
        $headers = @{ Authorization = "Bearer $token"; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
        $json = @{ autoSelect = $false; roomId = $roomId; startDate = $startDate; endDate = $endDate } | ConvertTo-Json
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$api/api/bookings" -Headers $headers -ContentType 'application/json' -Body $json
            [pscustomobject]@{ Status = [int]$response.StatusCode }
        } catch {
            $status = if ($null -ne $_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
            [pscustomobject]@{ Status = $status }
        }
    }
}
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
$results | Group-Object Status | Select-Object Name, Count
$history = Invoke-RestMethod -Uri "$base/api/bookings?page=0&size=100" -Headers $headers
$history.content | Where-Object { $_.roomId -eq $firstRoom.id -and $_.startDate -eq $parallelStart -and $_.status -eq 'CONFIRMED' }
```

В здоровом локальном контуре ожидаются один 201, остальные 409 и ровно одна CONFIRMED на этот период. 0 означает транспортную ошибку клиента. Если есть 503/504, изучите события и длительности: такой результат сам по себе не доказывает двойное бронирование. Даже при сбое активные интервалы не должны пересекаться.

Start-Job запускает отдельные процессы, поэтому старт запросов не абсолютно одновременный. Строго синхронизированный конкурентный тест блокировок находится в HotelTest. Если история превышает 100 записей, просмотрите следующие страницы.

## Шаг 12 Проверить равномерность распределения

Создадим отдельный отель с двумя новыми номерами и равными нулевыми счётчиками. Это также проверит административное создание каталога. Затем выполним шесть последовательных автоподборов на непересекающиеся новые даты:

```powershell
$balanceHotelJson = @{ name = 'Balance test'; address = 'Test Street 1' } | ConvertTo-Json
$balanceHotel = Invoke-RestMethod -Method Post -Uri "$base/api/hotels" -Headers $adminHeaders -ContentType 'application/json' -Body $balanceHotelJson
foreach ($number in @('B1', 'B2')) {
    $roomJson = @{ hotelId = $balanceHotel.id; number = $number; available = $true } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$base/api/rooms" -Headers $adminHeaders -ContentType 'application/json' -Body $roomJson | Out-Null
}
$selected = foreach ($i in 0..5) {
    $left = [DateTime]::UtcNow.Date.AddDays(30 + 3 * $i)
    $json = @{ autoSelect = $true; hotelId = $balanceHotel.id; startDate = $left.ToString('yyyy-MM-dd'); endDate = $left.AddDays(2).ToString('yyyy-MM-dd') } | ConvertTo-Json
    $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
    Invoke-RestMethod -Method Post -Uri "$base/api/bookings" -Headers $headers -ContentType 'application/json' -Body $json
}
$selected | Group-Object roomId | Select-Object Name, Count
```

Для двух одинаково доступных номеров и равных исходных счётчиков ожидается распределение 3 и 3. Это проверка конкретного симметричного сценария; алгоритм сортировки не обещает идеальную равномерность при любых фильтрах и параллельных изменениях.

## Шаг 13 Проверить ошибки без потери H2

Сценарии тайм-аута, потерянного ответа и неуспешного release воспроизводятся компонентными и сквозными тестами проекта. Не моделируйте временную сетевую ошибку одиночной остановкой Hotel и продолжением старой демонстрации: процесс потеряет in-memory календарь. Для нового чистого запуска остановите оба бизнес-сервиса и запустите их заново с одинаковыми demo-настройками. Старые пользовательские JWT после сброса данных лучше заменить новым входом.

Приложения останавливаются Ctrl+C в их собственных терминалах. Не завершайте все процессы Java на компьютере общей командой.

## Автоматические сквозные проверки

Для проверки всего взаимодействия сервисов выполните:

```powershell
mvn -Pe2e clean verify
```

Профиль `e2e` добавляет модуль `integration-tests`. Failsafe запускает четыре настоящих приложения на свободных локальных портах, создаёт временные ключи и независимые H2, затем останавливает только собственные процессы. Уже запущенные экземпляры на 8080–8082 и 8761 не используются. Нужен доступ к локальным TCP-соединениям и достаточно памяти для четырёх JVM.

Проверяются успешная бронь и повтор, права владельца, 12 одновременных запросов на один номер, отсутствующий номер, HTTP-статусы 404/405/415, недоступность Hotel, потеря ответа после реального commit, отказ release и автоматическое восстановление, равномерность распределения, Eureka и OpenAPI.

Только тестовый HTTP-прокси создаёт задержки и отказы. Производственные приложения не содержат переключателей искусственных сбоев. Состояния обеих баз проверяются чтением реальных API, счётчиками и состоянием внутреннего резерва. Компонентные тесты дополнительно проверяют фоновые задачи с управляемым временем, неизвестный исход вызова и переход за первые 100 кандидатов.

Отчёты JUnit находятся в `*/target/surefire-reports`, сквозные — в `integration-tests/target/failsafe-reports`. Логи четырёх тестовых приложений: `integration-tests/target/e2e`. Временные ключи удаляются при штатном завершении набора; весь `target` исключён из Git. Сквозные проверки являются ограниченным тестом конкуренции и отказов, а не длительным нагрузочным испытанием.

## Единый стиль кода

`mvn clean verify` проверяет форматирование Java через Spotless. Для автоматического исправления:

```powershell
mvn -Pe2e spotless:apply
```

После изменения форматирования повторите сборку. CI запускает `mvn -B -ntp -Pe2e clean verify`.

## Навигация

- [Описание API и модулей](../README.md)
- [Архитектура](architecture.md)
- [Календарь занятости](adr/001_calendar.md)
- [Подтверждение и компенсация](adr/002_saga.md)
- [Идемпотентность](adr/003_idempotency.md)
- [Повторный подбор кандидатов](adr/004_selection.md)
- [Ранее выполненные ручные проверки](manual-results.md)

## Если что-то не получилось

| Признак | Что проверить |
|---|---|
| Invalid target release 21 | JDK Maven и JDK проекта должны быть 21 |
| Cannot resolve getId | Annotation processing Lombok и полную перезагрузку Maven |
| Could not resolve placeholder PUBLIC_KEY | Переменная задаётся в том же терминале где запускается сервис |
| Invalid public RSA key | Публичный ключ X.509 из GenerateKeys; не вставляйте приватный вместо него |
| Gateway 503 | Eureka запущена backend зарегистрирован и registry успела обновиться |
| 401 после успешного входа | Одна пара ключей у всех приложений корректный Bearer и неистёкший токен |
| INTERNAL возвращает отказ | Пользовательский токен не предназначен для этих команд; это ожидаемо |
| 409 при бронировании | Проверьте даты available занятость и прежний Idempotency-Key |
| История исчезла после остановки | H2 in-memory очищается по условию задания |
| WrongTable или missing column | SQL лежит в правильном модуле и resources/db/migration имя V1__ с двумя подчёркиваниями |
| Тесты не обнаружены | Файлы заканчиваются Test.java и лежат в src/test/java |

## Проверка готовности

Перед сдачей самостоятельно подтвердите: чистую сборку, корректность ролей, только собственную историю, ручной и автоматический выбор, отсутствие пересечений, повторы без дублей, компенсацию, статистику, документацию и воспроизводимый запуск. Сохраните реальные результаты тестов и ручных запросов. Наличие текста тестов само по себе не означает, что они уже прошли.
