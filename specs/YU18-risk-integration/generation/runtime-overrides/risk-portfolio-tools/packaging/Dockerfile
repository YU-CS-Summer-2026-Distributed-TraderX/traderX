FROM python:3.14.6-slim-bookworm@sha256:4c92ffcde4dd6f1ff72a24518f49fd4990b27134987dfa31a733badde66df9f8
RUN test "$(uname -m)" = x86_64
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_CACHE_DIR=1 \
    JAX_PLATFORMS=cpu JAX_ENABLE_X64=true XDG_CACHE_HOME=/tmp/cache \
    OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1
WORKDIR /opt/pilot
COPY platform/requirements-linux-amd64.lock /opt/pilot/requirements.lock
RUN python -m pip install --require-hashes --only-binary=:all: -r requirements.lock
COPY engine-source/pyproject.toml /build/pyproject.toml
COPY engine-source/engine /build/engine
RUN python -m pip install --no-deps --no-build-isolation /build && python -m pip check && rm -rf /build
COPY platform/verify_installed.py /opt/pilot/verify_installed.py
COPY consumer /opt/pilot
COPY build.json /opt/pilot/build.json
RUN python /opt/pilot/verify_installed.py
USER 10001:10001
STOPSIGNAL SIGTERM
ENTRYPOINT ["python", "/opt/pilot/batch_cpu.py"]
