# 빌드 단계 — 소스에서 jar 를 만든다.
# 러너 이미지에 Gradle 과 소스를 남기지 않으려고 단계를 나눈다.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

# 의존성 파일을 먼저 복사한다. 소스만 바뀌었을 때 의존성 내려받기를
# 다시 하지 않도록 레이어를 분리하는 것이다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 실행 단계 — JRE 만 있으면 된다.
FROM eclipse-temurin:21-jre

WORKDIR /app

# root 로 돌리지 않는다. 컨테이너가 뚫렸을 때 피해를 줄인다.
RUN useradd --system --create-home --shell /usr/sbin/nologin nexplay
USER nexplay

# build.gradle 에서 plain jar 를 껐으므로 여기 매칭되는 건 실행 가능한 하나뿐이다.
COPY --from=build --chown=nexplay:nexplay /app/build/libs/*.jar app.jar

# 일일 동기화 크론이 Asia/Seoul 기준이다. 컨테이너 기본은 UTC 라
# 06:00 이 한국 시간 15:00 에 뜬다.
ENV TZ=Asia/Seoul

# 컨테이너에 준 메모리에 맞춰 힙을 잡는다. 고정값을 박으면 호스트를
# 옮길 때마다 다시 튜닝해야 한다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
