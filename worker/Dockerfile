FROM debian:bullseye
RUN apt update && apt install -y ffmpeg openjdk-17-jdk gradle

COPY ./build/libs/worker-0.0.1-all.jar /rewynd/worker.jar

WORKDIR /rewynd

CMD ["java", "-jar", "/rewynd/worker.jar"]
