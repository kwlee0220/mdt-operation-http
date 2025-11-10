FROM eclipse-temurin:21.0.7_6-jre-jammy

# Install Python 3.11
RUN apt-get update && \
    apt-get install -y software-properties-common && \
    add-apt-repository ppa:deadsnakes/ppa && \
    apt-get update && \
    apt-get install -y python3.11 python3.11-venv python3-pip && \
    ln -sf /usr/bin/python3.11 /usr/bin/python3 && \
    ln -sf /usr/bin/python3.11 /usr/bin/python && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install required Python packages
RUN pip3 install --no-cache-dir \
    opencv-python \
    matplotlib \
    PySide6

RUN mkdir -p /mdt/mdt-operation-http/config
RUN mkdir -p /mdt/mdt-operation-http/operations
COPY ./mdt-operation-http.jar /

ENV MDT_HOME=/mdt

WORKDIR /mdt/mdt-operation-http
ENTRYPOINT ["java", "-jar", "/mdt-operation-http.jar"]
