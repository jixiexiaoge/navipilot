#!/bin/bash

# Navipilot Web应用部署脚本

echo "开始部署Navipilot Web应用..."

# 更新pip
echo "更新pip..."
pip install --upgrade pip

# 安装依赖
echo "安装Python依赖..."
pip install -r requirements.txt

# 检查依赖是否安装成功
echo "检查关键依赖..."
python -c "import flask; print('Flask版本:', flask.__version__)"
python -c "import flask_sqlalchemy; print('Flask-SQLAlchemy版本:', flask_sqlalchemy.__version__)"

# 运行应用
echo "启动应用..."
python app.py
