FROM java:8u92-jre-alpine

RUN apk upgrade --update && \
    apk add iproute2 iptables && \
    ln -s /usr/lib/tc/ /lib

COPY target/our-service-0.1.0-SNAPSHOT-standalone.jar /uberjar.jar

WORKDIR /

CMD ["java", "-Xmx1024m", "-server", "-jar", "/uberjar.jar"]