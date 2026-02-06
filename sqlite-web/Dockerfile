FROM python:3.11-slim
RUN pip install --no-cache-dir sqlite-web
EXPOSE 8080
ENTRYPOINT ["sqlite_web", "/data/wgchores.db", "--host", "0.0.0.0", "--port", "8080"]
