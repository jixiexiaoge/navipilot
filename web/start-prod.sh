#!/bin/bash

# Navipilot Web应用生产环境启动脚本

echo "启动Navipilot Web应用生产环境..."

# 检查Python版本
python --version

# 更新pip
echo "更新pip..."
pip install --upgrade pip

# 安装生产环境依赖
echo "安装生产环境依赖..."
pip install -r requirements-prod.txt

# 检查关键依赖
echo "检查依赖安装状态..."
python -c "import flask; print('✓ Flask版本:', flask.__version__)"
python -c "import flask_sqlalchemy; print('✓ Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)"
python -c "import gunicorn; print('✓ Gunicorn版本:', gunicorn.__version__)"

# 使用Gunicorn启动应用
echo "使用Gunicorn启动应用..."
gunicorn -w 4 -b 0.0.0.0:5000 --timeout 120 --keep-alive 2 --max-requests 1000 --max-requests-jitter 100 app:app
