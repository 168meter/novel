FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY novel-common/pom.xml novel-common/pom.xml
COPY novel-front/pom.xml novel-front/pom.xml
COPY novel-admin/pom.xml novel-admin/pom.xml
COPY novel-crawl/pom.xml novel-crawl/pom.xml
RUN mvn -B -q \
        -Dmaven.test.skip=true \
        -Dmaven.antrun.skip=true \
        -pl novel-front -am dependency:go-offline
COPY novel-common/src novel-common/src
COPY novel-front/src novel-front/src
RUN mvn -B -q \
        -Dmaven.test.skip=true \
        -Dmaven.antrun.skip=true \
        -pl novel-front -am package \
    && jar_count="$(find novel-front/target -maxdepth 1 -type f \
        -name 'novel-front-*.jar' ! -name '*.original' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' | wc -l)" \
    && test "$jar_count" -eq 1 \
    && jar_path="$(find novel-front/target -maxdepth 1 -type f \
        -name 'novel-front-*.jar' ! -name '*.original' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar')" \
    && cp "$jar_path" /workspace/novel-front.jar \
    && test -s /workspace/novel-front.jar

FROM eclipse-temurin:21.0.8_9-jre-alpine
RUN apk add --no-cache gettext wget \
    && addgroup -S -g 10001 novel \
    && adduser -S -D -H -u 10001 -G novel novel
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/novel-front.jar /app/novel-front.jar
COPY --chown=10001:10001 templates /app/templates
COPY --chown=10001:10001 deploy/novel-front/entrypoint.sh /app/entrypoint.sh
COPY --chown=10001:10001 deploy/shardingsphere/shardingsphere-jdbc.yml.template /app/config/shardingsphere-jdbc.yml.template
RUN mkdir -p /app/runtime /app/logs \
    && chown -R 10001:10001 /app \
    && chmod 0555 /app/entrypoint.sh
USER 10001:10001
EXPOSE 8083 8084
HEALTHCHECK --interval=20s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8084/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
