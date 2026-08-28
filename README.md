# NEXPLAY Backend

NEXPLAY MVP용 Kotlin + Spring Boot 4.1 + MySQL API입니다. 실제 게임 카탈로그와 공식 Steam 뉴스 수집, 이벤트 분류, 일일 자동 동기화를 담당합니다. 프론트엔드는 [`nexplay-fe`](https://github.com/ronypoong/nexplay-fe) 저장소에 있습니다.

## 로컬 실행

MySQL `127.0.0.1:3306`이 실행 중인 상태에서:

```bash
cp .env.example .env   # 최초 1회, NEXPLAY_DB_PASSWORD 를 실제 값으로 수정
GRADLE_USER_HOME=.gradle ./gradlew bootRun
```

DB 계정 정보는 코드에 기본값이 없습니다. `application.yaml` 의
`spring.config.import` 가 프로젝트 루트의 `.env` 를 읽으므로 위처럼 파일로 넘기거나,
환경 변수로 직접 넘겨도 됩니다. `.env` 는 `.gitignore` 에 들어 있습니다.

기본 API 주소는 `http://localhost:4004`입니다. 시작 시 Flyway가 schema와 기준 데이터를 적용합니다.

## 환경 변수

| 변수 | 기본값 |
|---|---|
| `NEXPLAY_DB_URL` | `jdbc:mysql://127.0.0.1:3306/nexplay?...` |
| `NEXPLAY_DB_USERNAME` | 없음 (필수) |
| `NEXPLAY_DB_PASSWORD` | 없음 (필수) |
| `SERVER_PORT` | `4004` |
| `NEXPLAY_DAILY_SYNC_CRON` | `0 0 6 * * *` |
| `NEXPLAY_DAILY_SYNC_ZONE` | `Asia/Seoul` |
| `NEXPLAY_ADMIN_TOKEN` | 없음 (비우면 관리 API 차단) |
| `NEXPLAY_CORS_ALLOWED_ORIGINS` | `http://localhost:3003,http://127.0.0.1:3003` |

운영 환경에서는 DB 접속 정보와 관리 API 보호 설정을 별도로 구성해야 합니다.

## API

- `GET /api/v1/feed`
- `GET /api/v1/games?platform=&genre=&q=`
- `GET /api/v1/games/{slug}`
- `GET /api/v1/games/{slug}/events`
- `GET /api/v1/releases?from=&to=&platform=`
- `GET /api/v1/calendar?from=&to=&platform=`
- `GET /api/v1/trending?platform=&genre=`
- `GET /api/v1/discover?platform=&genre=`
- `GET /api/v1/admin/sources`
- `GET /api/v1/admin/collectors/runs`
- `POST /api/v1/admin/catalog/wikidata/sync?year=`
- `POST /api/v1/admin/catalog/wikidata/classifications?limit=&includeComplete=`
- `POST /api/v1/admin/catalog/steam/enrich?limit=`
- `POST /api/v1/admin/collectors/steam/run`
- `GET /actuator/health`

### 관리 API 인증

`/api/v1/admin` 이하는 토큰이 필요합니다.

```bash
curl -X POST 'http://localhost:4004/api/v1/admin/collectors/steam/run' \
  -H "X-NEXPLAY-Admin-Token: $NEXPLAY_ADMIN_TOKEN"
```

`NEXPLAY_ADMIN_TOKEN` 이 비어 있으면 관리 API 전체가 `503` 으로 막힙니다.
설정을 잊었을 때 열린 채로 뜨는 것보다 닫힌 채로 뜨는 편이 안전하기 때문입니다.
토큰이 틀리면 `401` 입니다.

## 데이터 수집

- Wikidata CC0에서 지정 연도 출시 후보와 복수 장르·플랫폼 탐색
- Steam Storefront API로 실제 Steam 게임, 이미지와 한국어 장르를 추가 검증
- Wikimedia Commons 이미지를 출처와 함께 사용
- Steam News RSS의 공식 소식을 수집하고 패치, 대규모 업데이트, DLC/확장팩 이벤트로 분류
- `(source_id, external_id)` 기준 중복 방지
- 매일 06:00 KST에 현재/다음 연도 카탈로그, 누락 분류, 뉴스를 순서대로 동기화

자동 동기화는 애플리케이션 프로세스가 살아 있을 때만 실행됩니다. 수동 실행은 다음과 같습니다.

```bash
curl -sS -X POST 'http://localhost:4004/api/v1/admin/catalog/wikidata/sync?year=2026' \
  -H "X-NEXPLAY-Admin-Token: $NEXPLAY_ADMIN_TOKEN"
curl -sS -X POST 'http://localhost:4004/api/v1/admin/catalog/wikidata/classifications?limit=1000'
curl -sS -X POST http://localhost:4004/api/v1/admin/collectors/steam/run
curl -sS http://localhost:4004/api/v1/admin/collectors/runs
```

## DB 변경과 검증

DB 변경은 `src/main/resources/db/migration`에 새 Flyway migration으로 추가합니다. 이미 적용된 migration은 수정하지 않습니다.

```bash
GRADLE_USER_HOME=.gradle ./gradlew test
```

일부 로컬 환경에서는 테스트가 실행 중인 `bootRun`을 종료할 수 있으므로 테스트 뒤 `4004` 포트를 다시 확인하세요.
