# Ваши действия после переноса всех файлов

Все команды в этой главе выполняете вы, когда захотите собрать и проверить проект. До этого момента достаточно создать файлы и вставить код из глав. Автоматический установщик проекта не нужен.

## Шаг 1 Проверить расположение файлов

В IntelliJ IDEA откройте корневой pom.xml как проект, выберите JDK 21 и выполните Reload Maven. Если редактор не видит getId/setId и другие методы Lombok, включите Settings → Build Execution Deployment → Compiler → Annotation Processors → Enable annotation processing. Сборка Maven тоже должна использовать JDK 21.

Проверьте, что рядом с корневым pom находятся ровно пять модулей из modules; common — библиотека. Java-классы должны лежать в src/main/java, тесты — в src/test/java, YAML и db/migration — в src/main/resources соответствующего сервиса. Не переносите все классы в один модуль.

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

Wrapper скачивает свою версию Maven, но установленная Java всё равно нужна. Сохраняйте .mvn, mvnw и mvnw.cmd в будущем репозитории.

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

Сценарии тайм-аута, потерянного ответа и неуспешного release воспроизводятся тестами главы 06. Не моделируйте временную сетевую ошибку одиночной остановкой Hotel и продолжением старой демонстрации: процесс потеряет in-memory календарь. Для нового чистого запуска остановите оба бизнес-сервиса и запустите их заново с одинаковыми demo-настройками. Старые пользовательские JWT после сброса данных лучше заменить новым входом.

Приложения останавливаются Ctrl+C в их собственных терминалах. Не завершайте все процессы Java на компьютере общей командой.

## Дополнительные файлы документации

Следующие тексты тоже создайте в своём проекте. Они нужны, чтобы другой человек мог понять решения и повторить запуск.

## Файл README.md

```markdown
# Система бронирования отелей

Учебный REST API на Java 21, Spring Boot 3.5 и Spring Cloud 2025.0.

## Приложения

- discovery-server на 8761 регистрирует сервисы
- api-gateway на 8080 принимает внешние запросы
- booking-service на 8081 хранит пользователей и бронирования
- hotel-service на 8082 хранит отели номера и календарь резервов
- common является библиотекой технических DTO и безопасности

## Подготовка

Нужны JDK 21 и Maven 3.9. Из корня выполнить `java tools/GenerateKeys.java`, затем `mvn clean verify`.

В окружении сервисов задать PUBLIC_KEY с file URI keys/public.pem. Booking дополнительно требует PRIVATE_KEY с file URI keys/private.pem.

Для демонстрационных данных установить DEMO_ENABLED=true. Booking также требует DEMO_ADMIN_PASSWORD и DEMO_USER_PASSWORD длиной минимум 8 символов и максимум 72 байта UTF-8. Будут созданы admin alice bob; последние два используют DEMO_USER_PASSWORD.

Запускать отдельные JAR из target: сначала discovery-server затем hotel-service booking-service api-gateway. Ключи не публиковать. Подробные команды и запросы приведены в docs/manual.md.

## API

Вход: POST /api/auth/register и /api/auth/login. Внешний адрес http://127.0.0.1:8080.

Каталог: /api/hotels и /api/rooms. Поиск номеров требует startDate и endDate; /api/rooms/recommend сортирует по timesBooked и id.

Бронирования: POST GET /api/bookings и GET DELETE /api/bookings/{id}. POST требует Idempotency-Key. Владелец определяется по JWT. autoSelect=true требует hotelId и игнорирует roomId.

Администрирование: /api/users /api/admin/rooms /api/admin/statistics/occupancy. Изменение каталога требует ADMIN.

Swagger: http://127.0.0.1:8081/swagger-ui/index.html и http://127.0.0.1:8082/swagger-ui/index.html.

## Согласованность

Booking сохраняет PENDING и вызывает hold затем commit в Hotel. После успеха сохраняется CONFIRMED. Ошибка приводит к CANCELLED и release; неудачное освобождение повторяется фоновым процессом. БД-транзакции не удерживаются во время HTTP-вызовов.

Повтор POST с тем же ключом возвращает исходный результат. Для актуального состояния используйте GET. Переходы и ошибки логируются с traceId bookingId reservationId.

## Проверки и ограничения

`mvn test` запускает тесты. Конкурентный тест Hotel использует настоящую H2; тесты Booking управляемо моделируют Hotel; сквозная ручная проверка описана в docs/manual.md.

H2 in-memory очищается после остановки. Независимые копии сервисов не образуют общий календарь. После потери одной бизнес-БД демонстрационные данные сбрасывают согласованно. Boot 3.5 используется по учебному заданию; для промышленного развёртывания требуется отдельная работа по обновлению основы и постоянному хранению.
```

В docs/manual.md скопируйте эту главу с вашими фактическими командами и полученными результатами. Не помещайте туда приватные ключи и реальные токены.

## Файл docs/architecture.md

```markdown
# Архитектура

Клиент → API Gateway → Booking Service или Hotel Service. Сервисы обнаруживаются через Eureka. Booking → внутренний API Hotel.

Пользователи и брони принадлежат Booking. Отели номера и интервалы резервов принадлежат Hotel. Межсервисных FK и общей БД нет.

Даты задаются интервалом [startDate,endDate). available отражает ремонт и вывод из эксплуатации. HELD и COMMITTED блокируют пересечения; RELEASED и EXPIRED не блокируют.

Gateway не публикует внутренний API. Бизнес-сервисы независимо проверяют JWT. USER работает только со своими бронями; ADMIN управляет каталогом и пользователями. Служебный JWT имеет отдельные issuer audience scope и subject.

Порядок резервирования: локальная запись PENDING → атомарный hold → commit → локальная запись CONFIRMED. При ошибке: CANCELLED → release → DONE. REQUIRED означает незавершённое освобождение.

Внешний ключ идемпотентности уникален в пределах пользователя. reservationId относится к одной попытке выделить номер и не меняется при сетевых повторах.
```

## Файл docs/adr/001_calendar.md

```markdown
# Календарь занятости в Hotel Service

Решение: хранить room_reservations у владельца номеров и сериализовать изменение одного номера блокировкой строки rooms.

Причина: чтение bookings другого сервиса через HTTP не может атомарно предотвратить две параллельные вставки. Блокировка применяется также к commit release истечению HELD и удалению номера.

Последствие: существует дополнительная техническая проекция выделения номера; согласованность пользовательской брони поддерживается сагой и компенсацией.
```

## Файл docs/adr/002_saga.md

```markdown
# Подтверждение резерва и компенсация

Решение: HELD имеет TTL а COMMITTED не истекает автоматически. Добавлен внутренний commit. Глобальных XA-транзакций нет.

При неизвестном результате HTTP применяется повтор той же идемпотентной команды либо безопасная отмена с release. До прояснения результата другой номер не выбирается.

release сохраняет терминальную запись даже если hold ещё не пришёл. Это предотвращает оживление позднего запроса. Восстановитель после дедлайна отменяет PENDING и повторяет освобождение; успешный сценарий заново не запускается.

timesBooked учитывает успешные подтверждения. Пользовательская отмена сохраняет историю; компенсация незавершённой саги убирает её вклад один раз.
```

## Файл docs/adr/003_idempotency.md

```markdown
# Идемпотентность пользовательского запроса

Решение: хранить requestKey payloadHash cachedStatus cachedBody в bookings. Уникальность задаётся парой userId requestKey.

Fingerprint включает только значимые поля намерения. При автоматическом подборе roomId исключён; при ручном выборе hotelId исключён.

Тот же ключ и fingerprint возвращают сохранённый результат либо 202 незавершённой операции. Другой fingerprint даёт 409. Подтверждённый исходный ответ после последующей отмены не переписывается; текущее состояние доступно через GET.

Повтор завершённого запроса ищется до бизнес-проверок изменившейся текущей даты. Ключи хранятся весь срок жизни набора H2.
```

## Файл .editorconfig

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true

[*.java]
indent_style = space
indent_size = 4

[{*.xml,*.yml,*.yaml}]
indent_style = space
indent_size = 2
```

В IDE можно применить Reformat Code после успешного переноса. Форматирование не должно менять поведение.

## Файл .github/workflows/build.yml

Создание этого файла не публикует репозиторий. Если позже вы сами загрузите проект на GitHub, workflow будет запускать тесты и сборку. Локальные ключи ему не нужны: тесты создают временные.

```yaml
name: build
on: [push, pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B clean verify
```

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
