
FROM amazoncorretto:21-alpine AS builder
WORKDIR /build

RUN apk add --no-cache maven
COPY . .

RUN mvn -B -pl match-engine -am package -DskipTests -q && \
    mv match-engine/target/match-engine.jar /build/match-engine.jar


FROM amazoncorretto:21-alpine AS runtime
WORKDIR /app
COPY --from=builder /build/match-engine.jar app.jar

ENV JAVA_OPTS="--illegal-access=permit \
  --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.util=ALL-UNNAMED \
  --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED \
  --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
  --add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED"

EXPOSE 8092
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
