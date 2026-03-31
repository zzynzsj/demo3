FROM registry.medway.cloud/containers/openjdk:8u212-jdk-alpine-chinese-font

ARG PROJECT_NAME="demo3"

ARG SERVICE_MOUDLE_NAME="."

ARG PROJECT_DIST="demo3-0.0.1-SNAPSHOT"

COPY ${SERVICE_MOUDLE_NAME}/target/${PROJECT_DIST}.jar /opt/app/${PROJECT_NAME}/bin/web.jar

WORKDIR /opt/app/${PROJECT_NAME}

CMD [ \
    "/sbin/tini", "--", \
    "bash", "-c", \
    "java $JAVA_OPTS \
    -Dfile.encoding=UTF-8 \
    -Djava.io.tmpdir=/tmp/ \
    -Duser.timezone=Asia/Shanghai \
    -Djava.library.path=/usr/local/lib/ \
    -Djava.security.egd=file:/dev/./urandom \
    -jar bin/web.jar" \
    ]
