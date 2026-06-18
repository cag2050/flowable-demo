FROM flowable/flowable-rest:6.8.0

USER root

# 【核心修改】将驱动复制到 WAR 包的 WEB-INF/lib 目录下
COPY --chmod=644 ./libs/mysql-connector-j-8.0.33.jar /app/WEB-INF/lib/mysql-connector-j-8.0.33.jar

USER flowable