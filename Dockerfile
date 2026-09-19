FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
# The base image is already JDK 21 — point the toolchain at it so maven-toolchains-plugin is satisfied.
RUN mkdir -p /root/.m2 && printf \
  '<toolchains><toolchain><type>jdk</type><provides><version>21</version></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>\n' \
  "$JAVA_HOME" > /root/.m2/toolchains.xml
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY cfg.yml ./
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS production
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
COPY cfg.yml ./
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
