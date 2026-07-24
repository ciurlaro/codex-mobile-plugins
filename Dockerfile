# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy@sha256:723151f3fc88ca2060153ee08ab8dbbea7983d6ed6f2622fe440acf178737c94 AS kotlin-build
WORKDIR /source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared ./shared
COPY mcp-server ./mcp-server
RUN --mount=type=cache,id=codex-mobile-provider-gradle,target=/root/.gradle \
    ./gradlew --no-daemon :mcp-server:installDist

FROM eclipse-temurin:17-jdk-jammy@sha256:723151f3fc88ca2060153ee08ab8dbbea7983d6ed6f2622fe440acf178737c94 AS tdlib-build
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates cmake curl g++ gperf make ninja-build perl zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN --mount=type=cache,id=codex-mobile-tdlib-linux,target=/build/tdlib \
    --mount=type=cache,id=codex-mobile-tdlib-java,target=/build/tdlib-java \
    curl --fail --location --retry 3 --retry-all-errors --proto '=https' --tlsv1.2 \
        https://github.com/openssl/openssl/releases/download/openssl-3.5.7/openssl-3.5.7.tar.gz \
        --output openssl.tar.gz \
    && echo 'a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8  openssl.tar.gz' | sha256sum --check \
    && mkdir openssl-source && tar -xzf openssl.tar.gz -C openssl-source --strip-components=1 \
    && cd openssl-source \
    && ./config no-shared no-module no-legacy no-tests no-apps no-docs --prefix=/opt/openssl --libdir=lib \
    && make -s -j"$(nproc)" install_sw
RUN curl --fail --location --retry 3 --retry-all-errors --proto '=https' --tlsv1.2 \
        https://github.com/tdlib/td/archive/022d60202e446ad1287b9fb68e687c8a0760788b.tar.gz \
        --output tdlib.tar.gz \
    && echo 'b0837cd880a6de8d45abdfd5024fe0f042c100eb5f241a5f185ba65579acfc32  tdlib.tar.gz' | sha256sum --check \
    && mkdir tdlib-source && tar -xzf tdlib.tar.gz -C tdlib-source --strip-components=1 \
    && grep -Fq 'project(TDLib VERSION 1.8.66' tdlib-source/CMakeLists.txt \
    && grep -Fq 'text.text, random_id' tdlib-source/td/telegram/MessagesManager.cpp \
    && grep -Fq 'message will be re-sent after restart' tdlib-source/td/telegram/MessagesManager.cpp \
    && cmake -S tdlib-source -B tdlib -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DOPENSSL_ROOT_DIR=/opt/openssl -DOPENSSL_INCLUDE_DIR=/opt/openssl/include \
        -DOPENSSL_SSL_LIBRARY=/opt/openssl/lib/libssl.a \
        -DOPENSSL_CRYPTO_LIBRARY=/opt/openssl/lib/libcrypto.a \
        -DOPENSSL_USE_STATIC_LIBS=TRUE -DTD_ENABLE_LTO=OFF -DTD_ENABLE_JNI=ON \
        -DTD_INSTALL_SHARED_LIBRARIES=OFF -DBUILD_TESTING=OFF \
    && cmake --build tdlib --target tdjson_static --parallel 4 \
    && cmake --install tdlib --prefix /opt/tdlib-sdk \
    && cmake -S tdlib-source/example/java -B tdlib-java -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=/opt/tdlib-sdk -DTD_JSON_JAVA=ON \
    && cmake --build tdlib-java --target tdjni --parallel 4 \
    && install -Dm 644 tdlib-java/libtdjsonjava.so /opt/tdlib/libtdjsonjava.so

FROM eclipse-temurin:17-jre-jammy@sha256:475d8e96b4b2bfe08999e5e854755c773af1581acdf959a4545d88f0696a2339
ARG DEBIAN_FRONTEND=noninteractive
LABEL org.opencontainers.image.source="https://github.com/ciurlaro/codex-mobile-plugins" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="GPL-3.0-or-later"
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates fontconfig fonts-dejavu-core libgomp1 libtesseract4 zlib1g \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/tessdata
ADD --checksum=sha256:7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 \
    https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/eng.traineddata \
    /opt/tessdata/eng.traineddata
COPY --from=kotlin-build /source/mcp-server/build/install/mcp-server /opt/provider
COPY --from=tdlib-build /opt/tdlib/libtdjsonjava.so /opt/provider/lib/native/libtdjsonjava.so
COPY THIRD_PARTY_NOTICES.md /opt/provider/THIRD_PARTY_NOTICES.md
ENV CODEX_MCP_WORKSPACE=/workspace \
    CODEX_MCP_STATE=/state \
    JAVA_OPTS="-Djava.awt.headless=true -Djava.library.path=/opt/provider/lib/native" \
    TESSDATA_PREFIX=/opt/tessdata
WORKDIR /workspace
ENTRYPOINT ["/opt/provider/bin/mcp-server"]
