FROM amazonlinux
ENV LANG=en_US.UTF-8
RUN yum update -y && yum install -y gcc gcc-c++ zlib-devel zip tar gzip && yum clean all && rm -rf /var/cache/yum

ENV JAVA_HOME=/usr/lib/graalvm
ENV GRAALVM_HOME=/usr/lib/graalvm
ENV GRAAL_VERSION=21.0.0
RUN curl -4 -L "https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-${GRAAL_VERSION}/graalvm-ce-java11-linux-amd64-${GRAAL_VERSION}.tar.gz" -o /tmp/graalvm.tar.gz \
    && tar -zxf /tmp/graalvm.tar.gz -C /tmp \
    && mv /tmp/graalvm-ce-java11-${GRAAL_VERSION} /usr/lib/graalvm \
    && rm -rf /tmp/*
RUN /usr/lib/graalvm/bin/gu install native-image

ENV CLOJURE_VERSION=1.10.3.814
ENV CLOJURE_URL="https://download.clojure.org"
WORKDIR /tmp
RUN curl -4 -L "${CLOJURE_URL}/install/linux-install-${CLOJURE_VERSION}.sh" -o /tmp/linux-install-${CLOJURE_VERSION}.sh \
        && chmod +x /tmp/linux-install-$CLOJURE_VERSION.sh \
        && /tmp/linux-install-$CLOJURE_VERSION.sh \
        && rm /tmp/linux-install-$CLOJURE_VERSION.sh
