git clone --depth 1 --branch v2.3.0 https://github.com/robomotionio/robomotion-java.git /tmp/robomotion-java
cd /tmp/robomotion-java && mvn install -DskipTests -q
export JAVA_HOME=/data/graalvm-jdk-21.0.10+8.1 && export GRAALVM_HOME=/data/graalvm-jdk-21.0.10+8.1 && export PATH="$JAVA_HOME/bin:$PATH" && cd
      /data/packages-main && go run rpi-build.go java-packages.yaml 2>&1

