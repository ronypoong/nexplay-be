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
#
# 75 -> 65: 자바가 쓰는 메모리는 힙만이 아니다. 메타스페이스, 스레드 스택,
# GC 자체, JIT 코드 캐시, 네트워크 버퍼가 힙 밖에 따로 붙는다. 75%를 힙에 주면
# 남은 25%로 그걸 다 감당해야 해서, 컨테이너가 죽는 건 힙이 아니라 이쪽이다.
#
# SerialGC: 코어 하나짜리 컨테이너에서 G1 은 백그라운드 스레드와 리전 메타데이터로
# 수십 MB를 먼저 가져간다. 힙이 수백 MB 규모면 그 값이 아깝다.
#
# 메타스페이스 상한: 안 잡으면 무제한이라, 새는 곳이 있을 때 힙이 아니라
# 컨테이너가 통째로 OOM 으로 죽는다. 원인을 찾기 가장 어려운 형태다.
# 상한(65%)은 그대로 둔다. 줄여야 하는 것은 죽지 않을 여유가 아니라, 한 번
# 커진 뒤 돌려주지 않는 버릇이다.
#
# 실측: 살아 있는 데이터는 56MB 인데(캐시를 다 채워도 같다) 힙은 245MB 까지
# 부풀어 있었다. 늘어난 만큼은 요청을 처리하며 생긴 쓰레기였고, 회수된 뒤에도
# 컨테이너에는 계속 잡혀 있었다.
#
# Initial 8%: 처음부터 크게 잡지 않는다. 필요하면 늘리면 된다.
# MaxHeapFreeRatio 30: 남는 공간이 30%를 넘으면 힙을 줄여 OS 에 돌려준다.
#   기본값 70 은 "일곱 칸이 빌 때까지" 안 줄인다는 뜻이라 사실상 안 줄어든다.
# ShrinkHeapInSteps 끔: 줄일 때 여러 번에 나눠 조금씩 말고 한 번에 줄인다.
#   하루 한 번 몰아치고 나머지는 노는 부하라 나눠 줄일 이유가 없다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=8 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:-ShrinkHeapInSteps -XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -Xss512k -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
