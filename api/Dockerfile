FROM debian:bullseye
RUN apt update && apt install -y openjdk-17-jdk gradle

# TODO figureout why the shadowjar isn't versioned
COPY ./build/libs/api-all.jar /rewynd/api.jar

WORKDIR /rewynd

CMD ["java", "-jar", "/rewynd/api.jar"]
