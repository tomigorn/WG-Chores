FROM eclipse-temurin:25-jdk@sha256:884a2f8692cc1e2c7e21f95914adf0ae0eafee9a7eb663a12169cc44468256ec

ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG API_LEVEL=36
ARG COMPILE_PLATFORM=android-37.0
ARG BUILD_TOOLS=36.0.0
ARG BUILDER_UID=10501

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_API_LEVEL=${API_LEVEL} \
    GRADLE_USER_HOME=/home/builder/.gradle
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      curl ca-certificates unzip git procps tzdata \
      libpulse0 libgl1 libegl1 libnss3 libdbus-1-3 libxkbcommon0 libxkbfile1 \
      libx11-6 libxext6 libxfixes3 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxrandr2 libxtst6 libxrender1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid ${BUILDER_UID} --user-group --shell /bin/bash builder \
 && mkdir -p ${ANDROID_HOME} /home/builder/.gradle /home/builder/.android \
 && chown -R builder:builder ${ANDROID_HOME} /home/builder

USER builder
WORKDIR /home/builder

RUN curl -fsSL -o /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c - \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/ct \
 && mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && mv /tmp/ct/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm -rf /tmp/cmdline-tools.zip /tmp/ct

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
      "platform-tools" \
      "platforms;android-${API_LEVEL}" \
      "platforms;${COMPILE_PLATFORM}" \
      "build-tools;${BUILD_TOOLS}" \
      "emulator" \
      "system-images;android-${API_LEVEL};google_apis;x86_64" \
    > /dev/null \
 && sdkmanager --list_installed

COPY --chown=builder:builder run-in-container.sh /opt/wgc/run-in-container.sh

ENTRYPOINT ["/opt/wgc/run-in-container.sh"]
