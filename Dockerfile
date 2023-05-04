FROM gradle:7.3.3-jdk11

COPY --chown=gradle:gradle . /benchmark
WORKDIR /benchmark

RUN gradle clean build

CMD ["gradle", "jmh"]
