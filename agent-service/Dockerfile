FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

COPY app ./app

RUN useradd -m appuser && chown -R appuser:appuser /app
USER appuser

ENV ANONYMIZED_TELEMETRY=False
EXPOSE 8200

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8200"]
