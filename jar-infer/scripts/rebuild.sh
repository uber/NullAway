VERSION=0.5.4-SNAPSHOT
./gradlew clean
./gradlew build
./gradlew :nullaway:assemble
./gradlew :jar-infer:jar-infer-cli:assemble
./gradlew :jar-infer:android-jarinfer-models-sdk28:assemble
mvn install:install-file -Dfile=${HOME}/src/NullAway/nullaway/build/libs/nullaway-${VERSION}.jar -DgroupId=com.uber.nullaway -DartifactId=nullaway -Dversion=${VERSION} -Dpackaging=jar
mvn install:install-file -Dfile=${HOME}/src/NullAway/jar-infer/jar-infer-cli/build/libs/jar-infer-cli-${VERSION}.jar -DgroupId=com.uber.nullaway -DartifactId=jar-infer-cli -Dversion=${VERSION} -Dpackaging=jar
mvn install:install-file -Dfile=${HOME}/src/NullAway/jar-infer/android-jarinfer-models-sdk28/build/libs/android-jarinfer-models-sdk28-${VERSION}.jar -DgroupId=com.uber.nullaway -DartifactId=android-jarinfer-models-sdk28 -Dversion=${VERSION} -Dpackaging=jar
