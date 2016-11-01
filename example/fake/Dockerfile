FROM java:8u92-jre-alpine

COPY target/fake-0.1.0-SNAPSHOT-standalone.jar /fake-uberjar.jar

WORKDIR /

CMD ["java", "-Xmx1024m", "-server", "-jar", "/fake-uberjar.jar"]