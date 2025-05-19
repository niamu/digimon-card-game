FROM rust:slim-bookworm AS libdcgcv

ENV OPEN_CV_VERSION="4.11.0"

RUN apt-get update && apt-get install -y \
    build-essential \
    pkg-config \
    clang \
    libclang-dev \
    libprotobuf-dev \
    libjpeg-dev \
    libssl-dev \
    wget \
    zip \
    cmake

WORKDIR /usr/src/opencv

RUN wget -O opencv.zip https://github.com/opencv/opencv/archive/refs/tags/${OPEN_CV_VERSION}.zip && \
    unzip opencv.zip && \
    rm opencv.zip

RUN wget -O opencv_contrib.zip https://github.com/opencv/opencv_contrib/archive/refs/tags/${OPEN_CV_VERSION}.zip && \
    unzip opencv_contrib.zip && \
    rm opencv_contrib.zip

WORKDIR /usr/src/opencv/build

RUN cmake -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/usr/local \
    -DBUILD_DOCS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_ITT=OFF \
    -DBUILD_IPP_IW=OFF \
    -DWITH_PNG=ON \
    -DWITH_JPEG=ON \
    -DWITH_TIFF=OFF \
    -DWITH_WEBP=OFF \
    -DWITH_OPENJPEG=OFF \
    -DWITH_JASPER=OFF \
    -DWITH_OPENEXR=OFF \
    -DWITH_V4L=OFF \
    -DWITH_CAROTENE=OFF \
    -DBUILD_opencv_java=ON \
    -DBUILD_opencv_python=OFF \
    -DOPENCV_EXTRA_MODULES_PATH=../opencv_contrib-${OPEN_CV_VERSION}/modules \
    ../opencv-${OPEN_CV_VERSION} \
    && make -j $(nproc) \
    && make install \
    && ldconfig

COPY . /db
WORKDIR /db

RUN make all



FROM clojure:tools-deps-bookworm-slim

ENV OPEN_CV_VERSION="4.11.0"

RUN apt-get update && apt-get install -y \
    build-essential \
    pkg-config \
    clang \
    libclang-dev \
    libprotobuf-dev \
    libpng-dev \
    libssl-dev \
    wget \
    zip \
    cmake

WORKDIR /usr/src/opencv

RUN wget -O opencv.zip https://github.com/opencv/opencv/archive/refs/tags/${OPEN_CV_VERSION}.zip && \
    unzip opencv.zip && \
    rm opencv.zip

RUN wget -O opencv_contrib.zip https://github.com/opencv/opencv_contrib/archive/refs/tags/${OPEN_CV_VERSION}.zip && \
    unzip opencv_contrib.zip && \
    rm opencv_contrib.zip

WORKDIR /usr/src/opencv/build

RUN cmake -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/usr/local \
    -DBUILD_DOCS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_ITT=OFF \
    -DBUILD_IPP_IW=OFF \
    -DWITH_PNG=ON \
    -DWITH_JPEG=ON \
    -DWITH_TIFF=OFF \
    -DWITH_WEBP=OFF \
    -DWITH_OPENJPEG=OFF \
    -DWITH_JASPER=OFF \
    -DWITH_OPENEXR=OFF \
    -DWITH_V4L=OFF \
    -DWITH_CAROTENE=OFF \
    -DBUILD_opencv_java=ON \
    -DBUILD_opencv_python=OFF \
    -DOPENCV_EXTRA_MODULES_PATH=../opencv_contrib-${OPEN_CV_VERSION}/modules \
    ../opencv-${OPEN_CV_VERSION} \
    && make -j $(nproc) \
    && make install \
    && ldconfig

COPY . /db
WORKDIR /db

COPY --from=libdcgcv /db/resources/libdcgcv.so /db/resources/libdcgcv.so

RUN clojure -Stree

CMD clojure -X:ingest
