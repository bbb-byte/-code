FROM python:3.11-slim

WORKDIR /opt/public-task-worker

RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm chromium \
    && rm -rf /var/lib/apt/lists/*

COPY crawler/requirements.txt /tmp/public-task-requirements.txt
RUN pip install --no-cache-dir -r /tmp/public-task-requirements.txt

COPY frontend/package*.json /workspace/frontend/
RUN cd /workspace/frontend && npm install

COPY worker/server.py /opt/public-task-worker/server.py

EXPOSE 8090

CMD ["python", "/opt/public-task-worker/server.py"]
