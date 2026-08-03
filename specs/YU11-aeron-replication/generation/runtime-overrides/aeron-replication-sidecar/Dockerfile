FROM eclipse-temurin:21-jre
WORKDIR /opt/app
COPY build/install/aeron-replication-sidecar/ ./
EXPOSE 18080 40123/udp 40124/udp 8010/udp 8011/udp
ENTRYPOINT ["/opt/app/bin/aeron-replication-sidecar"]
