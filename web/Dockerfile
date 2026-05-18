# 使用Python 3.13官方镜像
FROM python:3.13-slim

# 设置工作目录
WORKDIR /app

# 复制requirements.txt并安装依赖
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# 复制应用代码
COPY . .

# 暴露端口
EXPOSE 5000

# 设置环境变量
ENV FLASK_APP=app.py
ENV FLASK_ENV=production

# 运行应用
CMD ["python", "app.py"]
